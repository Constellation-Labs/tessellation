const axios = require('axios');
const { CONSTANTS } = require("./constants");
const { logWorkflow } = require("./logging");

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

/**
 * Basic retry with wall-clock timing.
 * Use for simple operations where ordinal tracking isn't needed.
 */
const withRetry = async (operation, {
    name = 'operation',
    maxAttempts = CONSTANTS.MAX_VERIFICATION_ATTEMPTS,
    interval = CONSTANTS.VERIFICATION_INTERVAL_MS,
    handleError = (error, attempt) => {
        if (error.response?.status === 404) {
            console.log(`${name} not found yet. Attempt ${attempt}`);
        } else {
            console.error(`${name} attempt ${attempt} failed: ${error.message}`);
        }
    }
} = {}) => {
    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
            return await operation();
        } catch (error) {
            handleError(error, attempt);

            if (attempt === maxAttempts) {
                throw new Error(`Max attempts reached for ${name}`);
            }

            await sleep(interval);
        }
    }
};

/**
 * Fetch the latest global snapshot ordinal from GL0.
 * @param {string} globalL0Url - The GL0 URL
 * @returns {Promise<{ordinal: number, hash: string}>}
 */
const getLatestSnapshotInfo = async (globalL0Url) => {
    const response = await axios.get(
        `${globalL0Url}/global-snapshots/latest`,
        {
            headers: {
                'Cache-Control': 'no-cache, no-store, must-revalidate',
                Pragma: 'no-cache',
                Expires: '0',
            },
        }
    );
    return {
        ordinal: response.data.value.ordinal,
        hash: response.data.value.hash || response.data.hash
    };
};

/**
 * Ordinal-aware retry for transaction verification.
 * 
 * This pattern is more robust than wall-clock retries because:
 * 1. It detects if the network is progressing (ordinal increasing)
 * 2. It can distinguish "tx dropped" (ordinal moved, tx missing) from "network stalled"
 * 3. It allows resubmission when a transaction is detected as dropped
 * 
 * @param {Function} checkFn - Async function that throws if condition not met.
 *                             Receives { ordinal, prevOrdinal } as argument.
 * @param {Object} options - Configuration options
 * @param {string} options.globalL0Url - GL0 URL for ordinal checks
 * @param {string} [options.name='operation'] - Name for logging
 * @param {number} [options.maxOrdinalMisses=5] - Max ordinal progressions without success before failing
 * @param {number} [options.maxStalledChecks=10] - Max checks with no ordinal progress before failing
 * @param {number} [options.interval=3000] - Polling interval in ms
 * @param {Function} [options.onOrdinalMiss] - Called when ordinal progresses but check fails.
 *                                              Can return a new tx hash if resubmitting.
 * @param {Function} [options.onStalled] - Called when ordinal hasn't progressed
 * @returns {Promise<any>} - Result from checkFn on success
 * 
 * @example
 * // Wait for stake to appear, resubmit if dropped
 * await withRetryOrdinal(
 *   async ({ ordinal }) => {
 *     const stakes = await getAccountDelegatedStakes(urls, address);
 *     const stake = stakes.activeDelegatedStakes.find(s => s.hash === stakeHash);
 *     if (!stake) throw new Error('Stake not found');
 *     return stake;
 *   },
 *   {
 *     globalL0Url: urls.globalL0Url,
 *     name: 'waitForStake',
 *     maxOrdinalMisses: 3,
 *     onOrdinalMiss: async ({ ordinalsMissed }) => {
 *       if (ordinalsMissed >= 2) {
 *         logWorkflow.warning('Stake likely dropped, resubmitting...');
 *         stakeHash = await resubmitStake();
 *         return stakeHash; // Update tracked hash
 *       }
 *     }
 *   }
 * );
 */
const withRetryOrdinal = async (checkFn, {
    globalL0Url,
    name = 'operation',
    maxOrdinalMisses = 10,
    maxStalledChecks = 20,
    interval = 3000,
    onOrdinalMiss = null,
    onStalled = null,
} = {}) => {
    if (!globalL0Url) {
        throw new Error('withRetryOrdinal requires globalL0Url');
    }

    let prevOrdinal = null;
    let ordinalMisses = 0;  // Times ordinal progressed but check still failed
    let stalledChecks = 0;  // Times ordinal didn't progress
    let totalChecks = 0;

    while (true) {
        totalChecks++;

        // Get current ordinal
        let currentSnapshot;
        try {
            currentSnapshot = await getLatestSnapshotInfo(globalL0Url);
        } catch (error) {
            logWorkflow.warning(`${name}: Failed to fetch snapshot ordinal: ${error.message}`);
            await sleep(interval);
            continue;
        }

        const currentOrdinal = currentSnapshot.ordinal;
        const ordinalProgressed = prevOrdinal !== null && currentOrdinal > prevOrdinal;

        // Try the check
        try {
            const result = await checkFn({ 
                ordinal: currentOrdinal, 
                prevOrdinal,
                ordinalProgressed,
                ordinalMisses,
                stalledChecks 
            });
            
            logWorkflow.info(`${name}: Success at ordinal ${currentOrdinal} (${totalChecks} checks)`);
            return result;
        } catch (error) {
            // Check failed - analyze why
            if (ordinalProgressed) {
                ordinalMisses++;
                stalledChecks = 0; // Reset stalled counter
                
                logWorkflow.info(
                    `${name}: Ordinal progressed ${prevOrdinal} → ${currentOrdinal} but check failed ` +
                    `(miss ${ordinalMisses}/${maxOrdinalMisses}): ${error.message}`
                );

                // Call the ordinal miss handler (e.g., to resubmit tx)
                if (onOrdinalMiss) {
                    try {
                        await onOrdinalMiss({ 
                            ordinal: currentOrdinal, 
                            prevOrdinal, 
                            ordinalsMissed: ordinalMisses,
                            error 
                        });
                    } catch (handlerError) {
                        logWorkflow.warning(`${name}: onOrdinalMiss handler failed: ${handlerError.message}`);
                    }
                }

                if (ordinalMisses >= maxOrdinalMisses) {
                    throw new Error(
                        `${name}: Failed after ${ordinalMisses} ordinal progressions. ` +
                        `Transaction likely dropped or invalid. Last error: ${error.message}`
                    );
                }
            } else {
                stalledChecks++;
                
                if (prevOrdinal !== null) {
                    logWorkflow.info(
                        `${name}: Ordinal stalled at ${currentOrdinal} ` +
                        `(stalled ${stalledChecks}/${maxStalledChecks}): ${error.message}`
                    );
                } else {
                    logWorkflow.info(
                        `${name}: Initial check at ordinal ${currentOrdinal}: ${error.message}`
                    );
                }

                if (onStalled) {
                    try {
                        await onStalled({ ordinal: currentOrdinal, stalledChecks });
                    } catch (handlerError) {
                        logWorkflow.warning(`${name}: onStalled handler failed: ${handlerError.message}`);
                    }
                }

                if (stalledChecks >= maxStalledChecks) {
                    throw new Error(
                        `${name}: Network stalled at ordinal ${currentOrdinal} for ${stalledChecks} checks. ` +
                        `Last error: ${error.message}`
                    );
                }
            }

            prevOrdinal = currentOrdinal;
            await sleep(interval);
        }
    }
};

/**
 * Wait for a transaction to be included in global snapshots.
 * Convenience wrapper around withRetryOrdinal for common tx verification pattern.
 * 
 * @param {Function} fetchFn - Function that fetches and returns the tx/state, throws if not found
 * @param {Object} options - Options passed to withRetryOrdinal plus:
 * @param {Function} [options.resubmitFn] - If provided, called to resubmit tx when likely dropped
 * @param {number} [options.resubmitAfterMisses=2] - Ordinal misses before resubmitting
 */
const waitForTxInclusion = async (fetchFn, {
    globalL0Url,
    name = 'waitForTx',
    resubmitFn = null,
    resubmitAfterMisses = 2,
    maxOrdinalMisses = 5,
    ...restOptions
} = {}) => {
    return withRetryOrdinal(
        fetchFn,
        {
            globalL0Url,
            name,
            maxOrdinalMisses,
            onOrdinalMiss: resubmitFn ? async ({ ordinalsMissed }) => {
                if (ordinalsMissed >= resubmitAfterMisses) {
                    logWorkflow.warning(`${name}: Resubmitting after ${ordinalsMissed} ordinal misses`);
                    await resubmitFn();
                }
            } : null,
            ...restOptions
        }
    );
};

module.exports = {
    sleep,
    withRetry,
    withRetryOrdinal,
    waitForTxInclusion,
    getLatestSnapshotInfo
}
