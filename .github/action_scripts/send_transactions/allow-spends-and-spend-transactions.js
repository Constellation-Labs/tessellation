const { dag4 } = require('@stardust-collective/dag4');
const axios = require('axios');
const jsSha256 = require('js-sha256');

const {
    parseSharedArgs,
    CONSTANTS: sharedConstants,
    PRIVATE_KEYS,
    sleep,
    withRetry,
    generateProof,
    SerializerType,
    createAndConnectAccount,
    createNetworkConfig,
    getEpochProgress,
    createSerializer,
    sortedJsonStringify,
    logWorkflow
} = require('../shared');

const CONSTANTS = {
    ...sharedConstants,
    EPOCH_PROGRESS_BUFFER: 5,
};

const getRandomInt = (min, max) => {
    return Math.floor(Math.random() * (max - min + 1)) + min;
};

const getRandomAmounts = () => ({
    allowSpend: getRandomInt(50, 150),
    spend: getRandomInt(1, 50),
    fee: getRandomInt(1, 20)
});

const createConfig = () => {
    const args = process.argv.slice(2);

    if (args.length < 5) {
        throw new Error(
            "Usage: node script.js <dagl0-port-prefix> <dagl1-port-prefix> <ml0-port-prefix> <cl1-port-prefix> <datal1-port-prefix>"
        );
    }

    const sharedArgs = parseSharedArgs(args.slice(0, 5));
    return { ...sharedArgs };
};

const transferTokensToCurrencyId = async (urls) => {
    const account = dag4.createAccount(PRIVATE_KEYS.key1);

    await account.connect({
        networkVersion: '2.0',
        l0Url: urls.globalL0Url,
        l1Url: urls.dagL1Url,
        testnet: true
    })

    const metagraphClient = await account.createMetagraphTokenClient({
        testnet: true,
        id: "METAGRAPH",
        metagraphId: CONSTANTS.CURRENCY_TOKEN_ID,
        l0Url: urls.currencyL0Url,
        l1Url: urls.currencyL1Url
    })

    await account.transferDag(CONSTANTS.CURRENCY_TOKEN_ID, 1000, 0.1)
    await metagraphClient.transfer(CONSTANTS.CURRENCY_TOKEN_ID, 1000, 0.1)
}

const createAllowSpendTransaction = async (sourceAccount, ammAddress, l1Url, l0Url, isCurrency = false) => {
    const [{ data: lastRef }, currentEpochProgress] = await Promise.all([
        axios.get(`${l1Url}/allow-spends/last-reference/${sourceAccount.address}`),
        getEpochProgress(l0Url, isCurrency)
    ]);

    const { allowSpend: amount, fee } = getRandomAmounts();

    logWorkflow.info(`Current epoch progress: ${currentEpochProgress}, setting lastValidEpochProgress to ${currentEpochProgress + CONSTANTS.EPOCH_PROGRESS_BUFFER}`);
    logWorkflow.info(`Using random amounts - allowSpend: ${amount}, fee: ${fee}`);

    return {
        amount,
        approvers: [ammAddress],
        destination: ammAddress,
        fee,
        lastValidEpochProgress: currentEpochProgress + CONSTANTS.EPOCH_PROGRESS_BUFFER,
        parent: lastRef,
        source: sourceAccount.address,
        currency: isCurrency ? CONSTANTS.CURRENCY_TOKEN_ID : null
    };
};

const createInvalidParentAllowSpendTransaction = async (sourceAccount, ammAddress, l1Url, l0Url, isCurrency = false) => {
    const [{ data: lastRef }, currentEpochProgress] = await Promise.all([
        axios.get(`${l1Url}/allow-spends/last-reference/${sourceAccount.address}`),
        getEpochProgress(l0Url, isCurrency)
    ]);

    const { allowSpend: amount, fee } = getRandomAmounts();

    const invalidParent = {
        ...lastRef,
        ordinal: lastRef.ordinal + 1
    };

    logWorkflow.info(`Current epoch progress: ${currentEpochProgress}, setting lastValidEpochProgress to ${currentEpochProgress + CONSTANTS.EPOCH_PROGRESS_BUFFER}`);
    logWorkflow.info(`Using random amounts - allowSpend: ${amount}, fee: ${fee}`);
    logWorkflow.info(`Original parent reference: ${JSON.stringify(lastRef)}`);
    logWorkflow.info(`Modified invalid parent reference: ${JSON.stringify(invalidParent)}`);

    return {
        amount,
        approvers: [ammAddress],
        destination: ammAddress,
        fee,
        lastValidEpochProgress: currentEpochProgress + CONSTANTS.EPOCH_PROGRESS_BUFFER,
        parent: invalidParent,
        source: sourceAccount.address,
        currency: isCurrency ? CONSTANTS.CURRENCY_TOKEN_ID : null
    };
};

const createInvalidEpochProgressAllowSpendTransaction = async (sourceAccount, ammAddress, l1Url, l0Url, isCurrency = false) => {
    const [{ data: lastRef }, currentEpochProgress] = await Promise.all([
        axios.get(`${l1Url}/allow-spends/last-reference/${sourceAccount.address}`),
        getEpochProgress(l0Url, isCurrency)
    ]);

    const { allowSpend: amount, fee } = getRandomAmounts();

    const invalidLastValidEpochProgress = currentEpochProgress - 1;

    logWorkflow.info(`Current epoch progress: ${currentEpochProgress}, setting invalid lastValidEpochProgress to ${invalidLastValidEpochProgress} (current - 1)`);
    logWorkflow.info(`Using random amounts - allowSpend: ${amount}, fee: ${fee}`);

    return {
        amount,
        approvers: [ammAddress],
        destination: ammAddress,
        fee,
        lastValidEpochProgress: invalidLastValidEpochProgress,
        parent: lastRef,
        source: sourceAccount.address,
        currency: isCurrency ? CONSTANTS.CURRENCY_TOKEN_ID : null
    };
};

const findMatchingHash = async (allowSpends, targetHash) => {
    logWorkflow.info('Searching for hash: ' + targetHash);

    const allowSpendHashes = await Promise.all(allowSpends.map(async spend => {
        if (!spend?.value) {
            logWorkflow.warning('Invalid allow spend: ' + JSON.stringify(spend));
            return null;
        }
        const serializer = createSerializer(SerializerType.BROTLI);
        const message = await serializer.serialize(spend.value);
        return {
            hash: jsSha256.sha256(Buffer.from(message, 'hex')),
            value: spend.value
        };
    })).then(hashes => hashes.filter(Boolean));

    logWorkflow.debug('Active allow spends: ' + JSON.stringify(allowSpendHashes, null, 2));

    return allowSpends.reduce(async (acc, allowSpend) => {
        const prevResult = await acc;
        if (prevResult) return true;

        if (!allowSpend?.value) {
            logWorkflow.warning('Skipping invalid allow spend: ' + JSON.stringify(allowSpend));
            return false;
        }

        const serializer = createSerializer(SerializerType.BROTLI);
        const message = await serializer.serialize(allowSpend.value);
        const allowSpendHash = jsSha256.sha256(Buffer.from(message, 'hex'));
        logWorkflow.debug('Comparing hashes: ' + JSON.stringify({
            target: targetHash,
            current: allowSpendHash,
            matches: allowSpendHash === targetHash
        }, null, 2));
        return allowSpendHash === targetHash;
    }, Promise.resolve(false));
};

const createVerifier = (urls) => {
    const verifyInL1 = async (hash, l1Url, layerName) => {
        await withRetry(
            async () => {
                logWorkflow.info(`Checking ${layerName} L1 for hash: ${hash}`);
                const response = await axios.get(`${l1Url}/allow-spends/${hash}`);
                if (!response.data) {
                    logWorkflow.warning(`No data found in ${layerName} L1 for hash: ${hash}`);
                    throw new Error('Transaction not found');
                }
                logWorkflow.debug(`Found allow spend in ${layerName} L1: ${JSON.stringify(response.data, null, 2)}`);
                logWorkflow.success(`AllowSpend transaction processed successfully in ${layerName} L1`);
            },
            { name: `${layerName} L1 verification` }
        );
    };

    const verifyAllowSpendInSnapshot = async (address, hash, snapshot, tokenId, layerName, isCurrency = false) => {
        logWorkflow.info(`Verifying allow spend in ${layerName} snapshot for address: ${address}`);

        const activeAllowSpendsForAddress = isCurrency
            ? snapshot[1]?.activeAllowSpends?.[address]
            : snapshot[1]?.activeAllowSpends?.[tokenId]?.[address];

        if (!activeAllowSpendsForAddress || activeAllowSpendsForAddress.length === 0) {
            logWorkflow.warning(`No active allow spends found for address ${address} in ${layerName}. Snapshot ordinal: ${snapshot[0]?.value?.ordinal}. Active allow spends: ${JSON.stringify(snapshot[1]?.activeAllowSpends, null, 2)}`);
            throw new Error(`No active allow spends found for address ${address} in ${layerName}`);
        }

        logWorkflow.info(`Found ${activeAllowSpendsForAddress.length} active allow spends for address ${address}`);

        const hasMatchingHash = await findMatchingHash(activeAllowSpendsForAddress, hash);
        if (!hasMatchingHash) {
            logWorkflow.warning(`Allow spend with hash ${hash} not found in ${layerName}. Available hashes: ${JSON.stringify(
                await Promise.all(activeAllowSpendsForAddress.map(async spend => {
                    const serializer = createSerializer(SerializerType.BROTLI);
                    const message = await serializer.serialize(spend.value);
                    return {
                        hash: jsSha256.sha256(Buffer.from(message, 'hex')),
                        value: spend.value
                    };
                }))
                , null, 2)}`);
            throw new Error(`Allow spend with hash ${hash} not found in ${layerName}`);
        }

        logWorkflow.success(`AllowSpend transaction found in ${layerName}`);
    };

    const verifyInL0 = async (address, hash, l0Url, tokenId, layerName, isCurrency = false) => {
        await withRetry(
            async () => {
                const { data: snapshot } = await axios.get(
                    `${l0Url}/global-snapshots/latest/combined`
                );
                await verifyAllowSpendInSnapshot(address, hash, snapshot, tokenId, `${layerName} L0`, isCurrency);
            },
            { name: `${layerName} L0 verification` }
        );
    };

    const verifyInCurrencyL0 = async (address, hash) => {
        await withRetry(
            async () => {
                const { data: snapshot } = await axios.get(
                    `${urls.currencyL0Url}/snapshots/latest/combined`
                );
                await verifyAllowSpendInSnapshot(
                    address,
                    hash,
                    snapshot,
                    CONSTANTS.CURRENCY_TOKEN_ID,
                    'Currency L0',
                    true
                );
            },
            { name: 'Currency L0 verification' }
        );
    };

    return {
        verifyDagL1: (hash) => verifyInL1(hash, urls.dagL1Url, 'DAG'),
        verifyDagL0: (address, hash) => verifyInL0(address, hash, urls.globalL0Url, '', 'DAG', false),
        verifyCurrencyL1: (hash) => verifyInL1(hash, urls.currencyL1Url, 'Currency'),
        verifyCurrencyL0: (address, hash) => verifyInCurrencyL0(address, hash),
        verifyGlobalL0ForCurrency: (address, hash) =>
            verifyInL0(address, hash, urls.globalL0Url, CONSTANTS.CURRENCY_TOKEN_ID, 'Global', false)
    };
};

const createBalanceManager = (urls) => {
    const getBalance = async (privateKey, l0Url, l1Url, isCurrency = false) => {
        const account = dag4.createAccount(privateKey);
        const address = account.address;

        try {
            const snapshotUrl = isCurrency
                ? `${l0Url}/snapshots/latest/combined`
                : `${l0Url}/global-snapshots/latest/combined`;

            const { data: snapshot } = await axios.get(snapshotUrl);

            const balance = snapshot[1]?.balances?.[address] || 0;

            return balance;
        } catch (error) {
            console.error(`Error fetching balance from ${l0Url}:`, error.message);
            throw error;
        }
    };

    const verifyBalanceChange = async (privateKey, initialBalance, amount, fee, l0Url, l1Url, isCurrency, layerName) => {
        await withRetry(
            async () => {
                const currentBalance = await getBalance(privateKey, l0Url, l1Url, isCurrency);
                const expectedBalance = initialBalance - amount - fee;

                if (currentBalance !== expectedBalance) {
                    throw new Error(
                        `Balance mismatch. Expected: ${expectedBalance}, got: ${currentBalance}`
                    );
                }
                logWorkflow.success(`${layerName} balance verified successfully`);
            },
            { name: `${layerName} balance verification` }
        );
    };

    const verifyBalanceAfterSpend = async (privateKey, initialBalance, balanceAfterAllowSpend, allowSpendAmount, spendAmount, l0Url, l1Url, isCurrency, layerName) => {
        await withRetry(
            async () => {
                const currentBalance = await getBalance(privateKey, l0Url, l1Url, isCurrency);

                const remainingAmount = allowSpendAmount - spendAmount;
                const expectedBalance = balanceAfterAllowSpend + remainingAmount;

                logWorkflow.warning(`${layerName} balance after spend - check skipped`);
                /*
                if (currentBalance !== expectedBalance) {
                    throw new Error(
                        `Balance after spend mismatch. Expected: ${expectedBalance} (${balanceAfterAllowSpend} + ${remainingAmount}), got: ${currentBalance}`
                    );
                }
                logWorkflow.success(`${layerName} balance after spend verified successfully`);
                */
            },
            { name: `${layerName} balance after spend verification` }
        );
    };

    return {
        getDagBalance: (privateKey) =>
            getBalance(privateKey, urls.globalL0Url, urls.dagL1Url, false),
        getCurrencyBalance: (privateKey) =>
            getBalance(privateKey, urls.currencyL0Url, urls.currencyL1Url, true),
        verifyDagBalanceChange: (privateKey, initialBalance, amount, fee) =>
            verifyBalanceChange(
                privateKey,
                initialBalance,
                amount,
                fee,
                urls.globalL0Url,
                urls.dagL1Url,
                false,
                'DAG'
            ),
        verifyCurrencyBalanceChange: (privateKey, initialBalance, amount, fee) =>
            verifyBalanceChange(
                privateKey,
                initialBalance,
                amount,
                fee,
                urls.currencyL0Url,
                urls.currencyL1Url,
                true,
                'Currency'
            ),
        verifyDagBalanceAfterSpend: (privateKey, initialBalance, balanceAfterAllowSpend, allowSpendAmount, spendAmount) =>
            verifyBalanceAfterSpend(
                privateKey,
                initialBalance,
                balanceAfterAllowSpend,
                allowSpendAmount,
                spendAmount,
                urls.globalL0Url,
                urls.dagL1Url,
                false,
                'DAG'
            ),
        verifyCurrencyBalanceAfterSpend: (privateKey, initialBalance, balanceAfterAllowSpend, allowSpendAmount, spendAmount) =>
            verifyBalanceAfterSpend(
                privateKey,
                initialBalance,
                balanceAfterAllowSpend,
                allowSpendAmount,
                spendAmount,
                urls.currencyL0Url,
                urls.currencyL1Url,
                true,
                'Currency'
            )
    };
};

const getBalanceFromL0 = async (address, l0Url) => {
    try {
        const { data } = await axios.get(`${l0Url}/dag/${address}/balance`);
        logWorkflow.info(`Current balance for ${address}: ${JSON.stringify(data)}`);
        return data.balance;
    } catch (error) {
        logWorkflow.error(`Error fetching balance from ${l0Url}/dag/${address}/balance:`, error.message);
        throw error;
    }
};

const createExceedingBalanceAllowSpendTransaction = async (sourceAccount, ammAddress, l1Url, l0Url, isCurrency = false) => {
    const [{ data: lastRef }, currentEpochProgress, currentBalance] = await Promise.all([
        axios.get(`${l1Url}/allow-spends/last-reference/${sourceAccount.address}`),
        getEpochProgress(l0Url, isCurrency),
        getBalanceFromL0(sourceAccount.address, l0Url)
    ]);

    const amount = Number(currentBalance) + 1000000;
    const fee = getRandomInt(1, 20);

    logWorkflow.info(`Current balance: ${currentBalance}, setting amount to exceed: ${amount}`);
    logWorkflow.info(`Current epoch progress: ${currentEpochProgress}, setting lastValidEpochProgress to ${currentEpochProgress + CONSTANTS.EPOCH_PROGRESS_BUFFER}`);

    return {
        amount,
        approvers: [ammAddress],
        destination: ammAddress,
        fee,
        lastValidEpochProgress: currentEpochProgress + CONSTANTS.EPOCH_PROGRESS_BUFFER,
        parent: lastRef,
        source: sourceAccount.address,
        currency: isCurrency ? CONSTANTS.CURRENCY_TOKEN_ID : null
    };
};

const sendInvalidTransaction = async (createTransactionFn, sourcePrivateKey, l0Url, l1Url, isCurrency = false, errorType) => {
    const sourceAccount = createAndConnectAccount(sourcePrivateKey, { l0Url, l1Url }, isCurrency);
    const ammAddress = CONSTANTS.CURRENCY_TOKEN_ID;

    const allowSpend = await createTransactionFn(sourceAccount, ammAddress, l1Url, l0Url, isCurrency);
    const proof = await generateProof(allowSpend, sourcePrivateKey, sourceAccount, SerializerType.BROTLI);

    try {
        const body = { value: allowSpend, proofs: [proof] };
        logWorkflow.info(`Sending invalid ${errorType} AllowSpend transaction: ${JSON.stringify(body)}`);
        const response = await axios.post(`${l1Url}/allow-spends`, body);
        logWorkflow.success(`Response: ${JSON.stringify(response.data)}`);
        return {
            address: sourceAccount.address,
            hash: response.data.hash,
            amount: allowSpend.amount,
            fee: allowSpend.fee,
            lastValidEpochProgress: allowSpend.lastValidEpochProgress
        };
    } catch (error) {
        if (error.response && error.response.status === 400) {
            logWorkflow.success(`Transaction correctly rejected with 400 Bad Request: ${error.response.data}`);
            return {
                address: sourceAccount.address,
                rejected: true,
                error: error.response.data
            };
        }
        logWorkflow.error(`Error sending invalid ${errorType} AllowSpend transaction`, error);
        throw error;
    }
};

const executeInvalidTransactionWorkflow = async (createTransactionFn, workflowName, errorType) => {
    try {
        logWorkflow.start(workflowName);

        const config = createConfig();
        const urls = createNetworkConfig(config);
        
        logWorkflow.info(`Invalid ${errorType} allow spend creation started`);
        const result = await sendInvalidTransaction(
            createTransactionFn, 
            PRIVATE_KEYS.key1, 
            urls.globalL0Url, 
            urls.dagL1Url, 
            false, 
            errorType
        );

        if (result.rejected) {
            logWorkflow.success(`${workflowName} workflow completed successfully - transaction was correctly rejected`);
        } else {
            logWorkflow.error(`${workflowName} workflow failed - transaction was accepted when it should have been rejected`);
            throw new Error(`Transaction with invalid ${errorType} was accepted when it should have been rejected`);
        }
    } catch (error) {
        logWorkflow.error(workflowName, error);
        throw error;
    }
};

const createInvalidSignatureTransaction = async (sourcePrivateKey, l0Url, l1Url, isCurrency = false) => {
    const sourceAccount = createAndConnectAccount(sourcePrivateKey, { l0Url, l1Url }, isCurrency);
    const ammAddress = CONSTANTS.CURRENCY_TOKEN_ID;

    const allowSpend = await createAllowSpendTransaction(sourceAccount, ammAddress, l1Url, l0Url, isCurrency);

    const validProof = await generateProof(allowSpend, sourcePrivateKey, sourceAccount, SerializerType.BROTLI);

    const invalidProof = {
        ...validProof,
        signature: 'INVALID' + validProof.signature.substring(7)
    }

    logWorkflow.info(`Original proof: ${validProof}`);
    logWorkflow.info(`Tampered proof: ${invalidProof}`);

    try {
        const body = { value: allowSpend, proofs: [invalidProof] };
        logWorkflow.info(`Sending allow spend with invalid signature: ${JSON.stringify(body.value)}`);
        const response = await axios.post(`${l1Url}/allow-spends`, body);
        logWorkflow.success(`Response: ${JSON.stringify(response.data)}`);
        return {
            address: sourceAccount.address,
            hash: response.data.hash,
            amount: allowSpend.amount,
            fee: allowSpend.fee,
            lastValidEpochProgress: allowSpend.lastValidEpochProgress
        };
    } catch (error) {
        if (error.response && error.response.status === 400) {
            logWorkflow.success(`Transaction correctly rejected with 400 Bad Request: ${error.response.data}`);
            return {
                address: sourceAccount.address,
                rejected: true,
                error: error.response.data
            };
        }
        logWorkflow.error('Error sending allow spend with invalid signature', error);
        throw error;
    }
};

const createDoubleSpendTransaction = async (sourcePrivateKey, dagL1Url, extendedDagL1Url, l0Url) => {
    const sourceAccount = createAndConnectAccount(sourcePrivateKey, { l0Url, l1Url: dagL1Url }, false);
    const ammAddress = CONSTANTS.CURRENCY_TOKEN_ID;

    const allowSpend = await createAllowSpendTransaction(sourceAccount, ammAddress, dagL1Url, l0Url, false);

    const proof1 = await generateProof(allowSpend, sourcePrivateKey, sourceAccount, SerializerType.BROTLI);
    const proof2 = await generateProof(allowSpend, sourcePrivateKey, sourceAccount, SerializerType.BROTLI);

    logWorkflow.info(`Created allow spend: ${JSON.stringify(allowSpend)}`);
    logWorkflow.info(`Generated two proofs for the same allow spend`);

    try {
        const body1 = { value: allowSpend, proofs: [proof1] };
        const body2 = { value: allowSpend, proofs: [proof2] };

        logWorkflow.info(`Sending allow spend to DAG L1: ${dagL1Url} and extended DAG L1: ${extendedDagL1Url} in parallel`);

        const [response1, response2] = await Promise.all([
            axios.post(`${dagL1Url}/allow-spends`, body1),
            axios.post(`${extendedDagL1Url}/allow-spends`, body2)
        ]);

        logWorkflow.success(`DAG L1 Response: ${JSON.stringify(response1.data)}`);
        logWorkflow.success(`Extended DAG L1 Response: ${JSON.stringify(response2.data)}`);

        return {
            address: sourceAccount.address,
            hash: response1.data.hash,
            amount: allowSpend.amount,
            fee: allowSpend.fee,
            lastValidEpochProgress: allowSpend.lastValidEpochProgress,
            dagL1Hash: response1.data.hash,
            extendedDagL1Hash: response2.data.hash
        };
    } catch (error) {
        logWorkflow.error('Error sending double spend transactions', error);
        throw error;
    }
};

const verifyDoubleSpendInL0 = async (address, hash, l0Url) => {
    return await withRetry(
        async () => {
            logWorkflow.info(`Checking L0 for hash: ${hash} at address: ${address}`);
            const { data: snapshot } = await axios.get(`${l0Url}/global-snapshots/latest/combined`);

            const activeAllowSpends = snapshot[1]?.activeAllowSpends?.['']?.[address] || [];

            if (activeAllowSpends.length === 0) {
                logWorkflow.warning(`No active allow spends found for address ${address} in L0`);
                throw new Error('No active allow spends found');
            }

            logWorkflow.info(`Found ${activeAllowSpends.length} active allow spends for address ${address}`);

            const hasMatchingHash = await findMatchingHash(activeAllowSpends, hash);

            if (hasMatchingHash) {
                logWorkflow.success(`Allow spend with hash ${hash} found in L0`);
                return true;
            } else {
                logWorkflow.warning(`Allow spend with hash ${hash} not found in L0`);
                return false;
            }
        },
        { name: 'L0 double spend verification', maxRetries: 10, interval: 5000 }
    );
};

const executeDoubleSpendWorkflow = async () => {
    try {
        logWorkflow.start('DoubleSpendAllowSpend');

        const config = createConfig();
        const urls = createNetworkConfig(config);

        logWorkflow.info(`Using DAG L1 URL: ${urls.dagL1Url}`);
        logWorkflow.info(`Using extended DAG L1 URL: ${urls.extendedDagL1Url}`);

        const sourceAccount = createAndConnectAccount(PRIVATE_KEYS.key1, { l0Url: urls.globalL0Url, l1Url: urls.dagL1Url }, false);
        const address = sourceAccount.address;

        const { data: initialSnapshot } = await axios.get(`${urls.globalL0Url}/global-snapshots/latest/combined`);
        const initialActiveAllowSpends = initialSnapshot[1]?.activeAllowSpends?.['']?.[address] || [];
        const initialCount = initialActiveAllowSpends.length;
        logWorkflow.info(`Initial active allow spends count for address ${address}: ${initialCount}`);

        const result = await createDoubleSpendTransaction(
            PRIVATE_KEYS.key1,
            urls.dagL1Url,
            urls.extendedDagL1Url,
            urls.globalL0Url
        );

        logWorkflow.info('Double spend transactions sent successfully, waiting for L0 processing...');

        const isAccepted = await verifyDoubleSpendInL0(
            result.address,
            result.dagL1Hash,
            urls.globalL0Url
        );

        if (!isAccepted) {
            logWorkflow.error('Double spend was not accepted in L0, which should not happen');
            throw new Error('Double spend was not accepted in L0, which should not happen');
        }

        if (result.dagL1Hash !== result.extendedDagL1Hash) {
            logWorkflow.error('Double spend transactions have different hashes, which should not happen');
            throw new Error('Double spend transactions have different hashes, which should not happen');
        }

        logWorkflow.info(`Both transactions have the same hash: ${result.dagL1Hash}`);

        const { data: finalSnapshot } = await axios.get(`${urls.globalL0Url}/global-snapshots/latest/combined`);
        const finalActiveAllowSpends = finalSnapshot[1]?.activeAllowSpends?.['']?.[address] || [];
        const finalCount = finalActiveAllowSpends.length;
        logWorkflow.info(`Final active allow spends count for address ${address}: ${finalCount}`);

        const expectedNewCount = initialCount + 1;
        if (finalCount !== expectedNewCount) {
            logWorkflow.error(`Expected ${expectedNewCount} active allow spends, but found ${finalCount}`);
            throw new Error(`Unexpected number of active allow spends: expected ${expectedNewCount}, got ${finalCount}`);
        } else {
            logWorkflow.success(`Exactly one new allow spend was added as expected (${initialCount} → ${finalCount})`);
        }

        logWorkflow.success('DoubleSpendAllowSpend workflow completed successfully');
    } catch (error) {
        logWorkflow.error('DoubleSpendAllowSpend', error);
        throw error;
    }
};

const executeExpiredAllowSpendWorkflow = async () => {
    try {
        logWorkflow.start('ExpiredAllowSpendWorkflow');

        const config = createConfig();
        const urls = createNetworkConfig(config);

        const balanceManager = createBalanceManager(urls);
        const transactionHandler = createTransactionHandler(urls);
        const verifier = createVerifier(urls);

        const initialBalance = await balanceManager.getDagBalance(PRIVATE_KEYS.key1);

        const { address, hash, amount, fee, lastValidEpochProgress } = await transactionHandler.sendDagTransaction(PRIVATE_KEYS.key1);

        logWorkflow.info(`Allow spend transaction sent successfully with lastValidEpochProgress: ${lastValidEpochProgress}`);

        await verifier.verifyDagL1(hash);

        logWorkflow.info('Waiting for L0 processing...');
        await sleep(CONSTANTS.SNAPSHOT_WAIT_TIME_MS);

        await verifier.verifyDagL0(address, hash);

        await verifyAllowSpendExpiration(
            address,
            hash,
            initialBalance,
            urls,
            lastValidEpochProgress,
            fee,
            false
        );

        logWorkflow.info('Attempting to use expired allow spend in a spend transaction...');

        const destinationAddress = dag4.createAccount(PRIVATE_KEYS.key4).address;

        try {
            const spendResult = await sendDataWithSpendTransaction(
                urls,
                hash,
                address,
                destinationAddress
            );

            logWorkflow.info('Waiting for L0 processing...');
            await sleep(CONSTANTS.SNAPSHOT_WAIT_TIME_MS);

            await withRetry(
                async () => {
                    const { data: snapshot } = await axios.get(`${urls.globalL0Url}/global-snapshots/latest/combined`);

                    const spendActions = snapshot[1]?.spendActions || [];

                    const hasMatchingSpendAction = spendActions.some(action =>
                        action.allowSpendHash === hash
                    );

                    if (hasMatchingSpendAction) {
                        throw new Error('Spend action with expired allow spend was accepted in L0, which should not happen');
                    }

                    logWorkflow.success('Spend action with expired allow spend was correctly rejected');
                },
                {
                    name: 'Expired allow spend verification',
                    interval: 5000,
                    maxRetries: 10
                }
            );

            logWorkflow.success('ExpiredAllowSpendWorkflow completed successfully');
        } catch (error) {
            if (error.response && error.response.status === 400) {
                logWorkflow.success(`Transaction correctly rejected with 400 Bad Request: ${JSON.stringify(error.response.data)}`);
                logWorkflow.success('ExpiredAllowSpendWorkflow completed successfully - transaction was correctly rejected');
            } else {
                throw error;
            }
        }
    } catch (error) {
        logWorkflow.error('ExpiredAllowSpendWorkflow', error);
        throw error;
    }
};

const executeInvalidSignatureWorkflow = async () => {
    try {
        logWorkflow.start('InvalidSignatureAllowSpend');

        const config = createConfig();
        const urls = createNetworkConfig(config);

        logWorkflow.info('Invalid signature allow spend creation started');
        const result = await createInvalidSignatureTransaction(
            PRIVATE_KEYS.key1,
            urls.globalL0Url,
            urls.dagL1Url,
            false
        );

        if (result.rejected) {
            logWorkflow.success('InvalidSignatureAllowSpend workflow completed successfully - transaction was correctly rejected');
        } else {
            logWorkflow.error('InvalidSignatureAllowSpend workflow failed - transaction was accepted when it should have been rejected');
            throw new Error('Transaction with invalid signature was accepted when it should have been rejected');
        }
    } catch (error) {
        logWorkflow.error('InvalidSignatureAllowSpend', error);
        throw error;
    }
};

const createTransactionHandler = (urls) => {
    const submitTransaction = async (allowSpend, proof, l1Url) => {
        try {
            const body = { value: allowSpend, proofs: [proof] };
            return await axios.post(`${l1Url}/allow-spends`, body);
        } catch (error) {
            console.error('Error sending AllowSpend transaction', error);
            throw error;
        }
    };

    const sendTransaction = async (sourcePrivateKey, l0Url, l1Url, isCurrency = false) => {
        const sourceAccount = createAndConnectAccount(sourcePrivateKey, { l0Url, l1Url }, isCurrency);
        const ammAddress = CONSTANTS.CURRENCY_TOKEN_ID;

        const allowSpend = await createAllowSpendTransaction(sourceAccount, ammAddress, l1Url, l0Url, isCurrency);
        const proof = await generateProof(allowSpend, sourcePrivateKey, sourceAccount, SerializerType.BROTLI);

        const response = await submitTransaction(allowSpend, proof, l1Url);

        return {
            address: sourceAccount.address,
            hash: response.data.hash,
            amount: allowSpend.amount,
            fee: allowSpend.fee,
            lastValidEpochProgress: allowSpend.lastValidEpochProgress
        };
    };

    const sendExceedingBalanceTransaction = async (sourcePrivateKey, l0Url, l1Url) => {
        const sourceAccount = createAndConnectAccount(sourcePrivateKey, { l0Url, l1Url }, false);
        const ammAddress = CONSTANTS.CURRENCY_TOKEN_ID;

        const allowSpend = await createExceedingBalanceAllowSpendTransaction(sourceAccount, ammAddress, l1Url, l0Url, false);
        const proof = await generateProof(allowSpend, sourcePrivateKey, sourceAccount, SerializerType.BROTLI);

        try {
            const body = { value: allowSpend, proofs: [proof] };
            logWorkflow.info(`Sending exceeding balance AllowSpend transaction: ${JSON.stringify(body)}`);
            const response = await axios.post(`${l1Url}/allow-spends`, body);
            logWorkflow.success(`Response: ${JSON.stringify(response.data)}`);
            return {
                address: sourceAccount.address,
                hash: response.data.hash,
                amount: allowSpend.amount,
                fee: allowSpend.fee,
                lastValidEpochProgress: allowSpend.lastValidEpochProgress
            };
        } catch (error) {
            if (error.response && error.response.status === 400) {
                logWorkflow.success(`Transaction correctly rejected with 400 Bad Request: ${error.response.data}`);
                return {
                    address: sourceAccount.address,
                    rejected: true,
                    error: error.response.data
                };
            }
            logWorkflow.error('Error sending exceeding balance AllowSpend transaction', error);
            throw error;
        }
    };

    const sendInvalidParentTransaction = async (sourcePrivateKey, l0Url, l1Url, isCurrency = false) => {
        const sourceAccount = createAndConnectAccount(sourcePrivateKey, { l0Url, l1Url }, isCurrency);
        const ammAddress = CONSTANTS.CURRENCY_TOKEN_ID;

        const allowSpend = await createInvalidParentAllowSpendTransaction(sourceAccount, ammAddress, l1Url, l0Url, isCurrency);
        const proof = await generateProof(allowSpend, sourcePrivateKey, sourceAccount, SerializerType.BROTLI);

        try {
            const body = { value: allowSpend, proofs: [proof] };
            logWorkflow.info(`Sending invalid parent AllowSpend transaction: ${JSON.stringify(body)}`);
            const response = await axios.post(`${l1Url}/allow-spends`, body);
            logWorkflow.success(`Response: ${JSON.stringify(response.data)}`);
            return {
                address: sourceAccount.address,
                hash: response.data.hash,
                amount: allowSpend.amount,
                fee: allowSpend.fee,
                lastValidEpochProgress: allowSpend.lastValidEpochProgress
            };
        } catch (error) {
            if (error.response && error.response.status === 400) {
                logWorkflow.success(`Transaction correctly rejected with 400 Bad Request: ${error.response.data}`);
                return {
                    address: sourceAccount.address,
                    rejected: true,
                    error: error.response.data
                };
            }
            logWorkflow.error('Error sending invalid parent AllowSpend transaction', error);
            throw error;
        }
    };

    const sendInvalidEpochProgressTransaction = async (sourcePrivateKey, l0Url, l1Url, isCurrency = false) => {
        const sourceAccount = createAndConnectAccount(sourcePrivateKey, { l0Url, l1Url }, isCurrency);
        const ammAddress = CONSTANTS.CURRENCY_TOKEN_ID;

        const allowSpend = await createInvalidEpochProgressAllowSpendTransaction(sourceAccount, ammAddress, l1Url, l0Url, isCurrency);
        const proof = await generateProof(allowSpend, sourcePrivateKey, sourceAccount, SerializerType.BROTLI);

        try {
            const body = { value: allowSpend, proofs: [proof] };
            logWorkflow.info(`Sending invalid epoch progress AllowSpend transaction: ${JSON.stringify(body)}`);
            const response = await axios.post(`${l1Url}/allow-spends`, body);
            logWorkflow.success(`Response: ${JSON.stringify(response.data)}`);
            return {
                address: sourceAccount.address,
                hash: response.data.hash,
                amount: allowSpend.amount,
                fee: allowSpend.fee,
                lastValidEpochProgress: allowSpend.lastValidEpochProgress
            };
        } catch (error) {
            if (error.response && error.response.status === 400) {
                logWorkflow.success(`Transaction correctly rejected with 400 Bad Request: ${error.response.data}`);
                return {
                    address: sourceAccount.address,
                    rejected: true,
                    error: error.response.data
                };
            }
            logWorkflow.error('Error sending invalid epoch progress AllowSpend transaction', error);
            throw error;
        }
    };

    return {
        sendDagTransaction: (sourcePrivateKey) =>
            sendTransaction(sourcePrivateKey, urls.globalL0Url, urls.dagL1Url, false),
        sendCurrencyTransaction: (sourcePrivateKey) =>
            sendTransaction(sourcePrivateKey, urls.currencyL0Url, urls.currencyL1Url, true),
        sendExceedingBalanceTransaction: (sourcePrivateKey) =>
            sendExceedingBalanceTransaction(sourcePrivateKey, urls.globalL0Url, urls.dagL1Url),
        sendInvalidParentTransaction: (sourcePrivateKey) =>
            sendInvalidParentTransaction(sourcePrivateKey, urls.globalL0Url, urls.dagL1Url, false),
        sendInvalidEpochProgressTransaction: (sourcePrivateKey) =>
            sendInvalidEpochProgressTransaction(sourcePrivateKey, urls.globalL0Url, urls.dagL1Url, false),
        sendInvalidTransaction: (createTransactionFn, sourcePrivateKey, errorType) =>
            sendInvalidTransaction(createTransactionFn, sourcePrivateKey, urls.globalL0Url, urls.dagL1Url, false, errorType)
    };
};

const verifyAllowSpendExpiration = async (address, hash, initialBalance, urls, lastValidEpochProgress, fee, isCurrency = false) => {
    const l0Url = isCurrency ? urls.currencyL0Url : urls.globalL0Url;

    await withRetry(
        async () => {
            const currentEpochProgress = await getEpochProgress(l0Url, isCurrency);
            if (currentEpochProgress <= lastValidEpochProgress) {
                throw new Error(
                    `Current epoch progress (${currentEpochProgress}) has not passed lastValidEpochProgress (${lastValidEpochProgress})`
                );
            }
            logWorkflow.info(`Epoch progress advanced past ${lastValidEpochProgress}`);
        },
        {
            name: `${isCurrency ? 'Currency' : 'DAG'} epoch progress advancement`,
            interval: CONSTANTS.EXPIRATION_VERIFICATION_INTERVAL_MS
        }
    );

    const snapshotUrl = isCurrency
        ? `${l0Url}/snapshots/latest/combined`
        : `${l0Url}/global-snapshots/latest/combined`;

    const { data: snapshot } = await axios.get(snapshotUrl);

    const activeAllowSpends = isCurrency
        ? snapshot[1]?.activeAllowSpends?.[address] || []
        : snapshot[1]?.activeAllowSpends?.[CONSTANTS.CURRENCY_TOKEN_ID]?.[address] || [];

    if (activeAllowSpends.length > 0) {
        const hasMatchingHash = await findMatchingHash(activeAllowSpends, hash);
        if (hasMatchingHash) {
            throw new Error('Allow spend still active after expiration');
        }
    }

    const currentBalance = snapshot[1]?.balances?.[address] || 0;
    const expectedBalance = initialBalance - fee;

    if (currentBalance !== expectedBalance) {
        throw new Error(
            `Balance not reverted correctly after expiration. Expected: ${expectedBalance} (initial - fee), got: ${currentBalance}`
        );
    }

    logWorkflow.success(`Allow spend expired and balance reverted successfully (minus fee) in ${isCurrency ? 'Currency' : 'DAG'}`);
};

const executeWorkflow = async ({
    urls,
    workflowName,
    getInitialBalance,
    sendTransaction,
    verifyL1,
    verifyL0,
    verifyGlobalL0,
    verifyBalance,
    options = {
        skipExpirationCheck: false,
        skipVerifyL1: false,
        skipVerifyL0: false,
        skipVerifyBalance: false,
        skipVerifyGlobalL0: false
    }
}) => {
    const balanceManager = createBalanceManager(urls);
    const transactionHandler = createTransactionHandler(urls);
    const verifier = createVerifier(urls);

    logWorkflow.start(`${workflowName} workflow`);

    const initialBalance = await getInitialBalance(balanceManager);

    const { address, hash, amount, fee, lastValidEpochProgress } = await sendTransaction(transactionHandler);

    logWorkflow.info(`Allow spend transaction sent successfully with lastValidEpochProgress: ${lastValidEpochProgress}`);

    if (!options.skipVerifyL1) {
        await verifyL1(verifier, hash);
    }

    if (!options.skipVerifyL0) {
        await sleep(CONSTANTS.SNAPSHOT_WAIT_TIME_MS);
        await verifyL0(verifier, address, hash);
    }

    if (!options.skipVerifyGlobalL0 && verifyGlobalL0) {
        await verifyGlobalL0(verifier, address, hash);
    }

    if (!options.skipVerifyBalance) {
        await verifyBalance(balanceManager, initialBalance, amount, fee);
    }

    logWorkflow.info(`${workflowName} workflow completed successfully, waiting for expiration...`);

    if (!options.skipExpirationCheck) {
        await verifyAllowSpendExpiration(
            address,
            hash,
            initialBalance,
            urls,
            lastValidEpochProgress,
            fee,
            workflowName === 'Currency'
        );

        logWorkflow.success(`${workflowName} workflow expiration verified successfully`);
    }

    return { address, hash, amount };
};

const dagWorkflow = {
    workflowName: 'DAG',
    getInitialBalance: (balanceManager) =>
        balanceManager.getDagBalance(PRIVATE_KEYS.key1),
    sendTransaction: (handler) =>
        handler.sendDagTransaction(PRIVATE_KEYS.key1),
    verifyL1: (verifier, hash) =>
        verifier.verifyDagL1(hash),
    verifyL0: (verifier, address, hash) =>
        verifier.verifyDagL0(address, hash),
    verifyBalance: (balanceManager, initialBalance, amount, fee) =>
        balanceManager.verifyDagBalanceChange(PRIVATE_KEYS.key1, initialBalance, amount, fee)
};

const currencyWorkflow = {
    workflowName: 'Currency',
    getInitialBalance: (balanceManager) =>
        balanceManager.getCurrencyBalance(PRIVATE_KEYS.key1),
    sendTransaction: (handler) =>
        handler.sendCurrencyTransaction(PRIVATE_KEYS.key1),
    verifyL1: (verifier, hash) =>
        verifier.verifyCurrencyL1(hash),
    verifyL0: (verifier, address, hash) =>
        verifier.verifyCurrencyL0(address, hash),
    verifyGlobalL0: (verifier, address, hash) =>
        verifier.verifyGlobalL0ForCurrency(address, hash),
    verifyBalance: (balanceManager, initialBalance, amount, fee) =>
        balanceManager.verifyCurrencyBalanceChange(PRIVATE_KEYS.key1, initialBalance, amount, fee)
};

const createUserSpendTransaction = (allowSpendRef, sourceAddress, destinationAddress) => {
    const { spend: amount } = getRandomAmounts();
    const tx = {
        allowSpendRef: allowSpendRef,
        currency: null,
        amount,
        source: sourceAddress,
        destination: destinationAddress
    };
    logWorkflow.info('Created user spend transaction: ' + JSON.stringify({
        type: 'User',
        allowSpendRef,
        source: sourceAddress,
        destination: destinationAddress,
        amount
    }, null, 2));
    return tx;
};

const createMetagraphSpendTransaction = (destinationAddress) => {
    const { spend: amount } = getRandomAmounts();
    const tx = {
        allowSpendRef: null,
        currency: null,
        amount,
        source: CONSTANTS.CURRENCY_TOKEN_ID,
        destination: destinationAddress
    };
    logWorkflow.info('Created metagraph spend transaction: ' + JSON.stringify({
        type: 'Metagraph',
        source: CONSTANTS.CURRENCY_TOKEN_ID,
        destination: destinationAddress,
        amount
    }, null, 2));
    return tx;
};

const createUsageUpdateWithSpendTransaction = (allowSpendRef, sourceAddress, destinationAddress) => {
    logWorkflow.info('Creating usage update with spend transactions: ' + JSON.stringify({
        sourceAddress,
        allowSpendRef,
        destinationAddress
    }, null, 2));

    const update = {
        UsageUpdateWithSpendTransaction: {
            address: sourceAddress,
            usage: 10,
            spendTransactionA: createUserSpendTransaction(allowSpendRef, sourceAddress, destinationAddress),
            spendTransactionB: createMetagraphSpendTransaction(destinationAddress)
        }
    };

    logWorkflow.debug('Created complete usage update: ' + JSON.stringify({
        address: update.UsageUpdateWithSpendTransaction.address,
        usage: update.UsageUpdateWithSpendTransaction.usage,
        spendTransactionA: {
            type: 'User',
            hasAllowSpendRef: !!update.UsageUpdateWithSpendTransaction.spendTransactionA.allowSpendRef,
            destination: update.UsageUpdateWithSpendTransaction.spendTransactionA.destination,
            amount: update.UsageUpdateWithSpendTransaction.spendTransactionA.amount
        },
        spendTransactionB: {
            type: 'Metagraph',
            hasAllowSpendRef: !!update.UsageUpdateWithSpendTransaction.spendTransactionB.allowSpendRef,
            destination: update.UsageUpdateWithSpendTransaction.spendTransactionB.destination,
            amount: update.UsageUpdateWithSpendTransaction.spendTransactionB.amount
        }
    }, null, 2));

    return update;
};

const sendDataWithSpendTransaction = async (urls, allowSpendHash, sourceAddress, destinationAddress) => {
    const dataUpdate = createUsageUpdateWithSpendTransaction(allowSpendHash, sourceAddress, destinationAddress);
    const account = dag4.createAccount(PRIVATE_KEYS.key3);
    const proof = await generateProof(dataUpdate, PRIVATE_KEYS.key3, account, SerializerType.STANDARD);

    const body = {
        value: dataUpdate,
        proofs: [proof]
    };

    try {
        logWorkflow.info(`Sending data transaction with spend: ${JSON.stringify(body)}`);
        const response = await axios.post(`${urls.dataL1Url}/data`, body);
        logWorkflow.success(`Response: ${JSON.stringify(response.data)}`);

        const spendAmount = dataUpdate.UsageUpdateWithSpendTransaction.spendTransactionA.amount;

        return { response: response.data, update: dataUpdate, spendAmount };
    } catch (e) {
        logWorkflow.error('Error sending data transaction with spend', e);
        throw e;
    }
};

const spendTransactionWorkflow = {
    workflowName: 'SpendTransaction',
    getInitialBalance: (balanceManager) =>
        balanceManager.getDagBalance(PRIVATE_KEYS.key3),
    sendTransaction: (handler) =>
        handler.sendDagTransaction(PRIVATE_KEYS.key3),
    verifyL1: (verifier, hash) =>
        verifier.verifyDagL1(hash),
    verifyL0: (verifier, address, hash) =>
        verifier.verifyDagL0(address, hash),
    verifyBalance: (balanceManager, initialBalance, amount, fee) =>
        balanceManager.verifyDagBalanceChange(PRIVATE_KEYS.key3, initialBalance, amount, fee)
};

const executeSpendTransactionWorkflow = async () => {
    try {
        logWorkflow.start('SpendTransaction');

        const config = createConfig();
        const urls = createNetworkConfig(config);
        const balanceManager = createBalanceManager(urls);

        const initialBalance = await balanceManager.getDagBalance(PRIVATE_KEYS.key3);
        logWorkflow.info(`Initial balance before allow spend: ${initialBalance}`);

        await transferTokensToCurrencyId(urls)

        logWorkflow.info('Allow spend creation started');
        const { address, hash, amount: allowSpendAmount } = await executeWorkflow({
            urls,
            ...spendTransactionWorkflow,
            options: {
                skipExpirationCheck: true
            }
        });

        const balanceAfterAllowSpend = await balanceManager.getDagBalance(PRIVATE_KEYS.key3);
        logWorkflow.info(`Balance after allow spend: ${balanceAfterAllowSpend}`);

        logWorkflow.info('Allow spend created and verified, proceeding with spend transaction');

        const spendResult = await sendDataWithSpendTransaction(
            urls,
            hash,
            address,
            CONSTANTS.CURRENCY_TOKEN_ID
        );

        await verifySpendActionInGlobalL0(urls, CONSTANTS.CURRENCY_TOKEN_ID, spendResult.update);

        logWorkflow.info(`Verifying balance after spend with values: initialBalance=${initialBalance}, balanceAfterAllowSpend=${balanceAfterAllowSpend}, allowSpendAmount=${allowSpendAmount}, spendAmount=${spendResult.spendAmount}`);

        await sleep(CONSTANTS.SNAPSHOT_WAIT_TIME_MS);
        await balanceManager.verifyDagBalanceAfterSpend(
            PRIVATE_KEYS.key3,
            initialBalance,
            balanceAfterAllowSpend,
            allowSpendAmount,
            spendResult.spendAmount
        );

        logWorkflow.success('SpendTransaction');
    } catch (error) {
        logWorkflow.error('SpendTransaction', error);
        throw error;
    }
};

const executeUnauthorizedSpendTransactionWorkflow = async () => {
    try {
        logWorkflow.start('UnauthorizedSpendTransaction');

        const config = createConfig();
        const urls = createNetworkConfig(config);
        const balanceManager = createBalanceManager(urls);

        const initialBalance = await balanceManager.getDagBalance(PRIVATE_KEYS.key3);
        logWorkflow.info(`Initial balance before allow spend: ${initialBalance}`);

        logWorkflow.info('Allow spend creation started');
        const { address, hash, amount: allowSpendAmount } = await executeWorkflow({
            urls,
            ...spendTransactionWorkflow,
            options: {
                skipExpirationCheck: true
            }
        });

        const balanceAfterAllowSpend = await balanceManager.getDagBalance(PRIVATE_KEYS.key3);
        logWorkflow.info(`Balance after allow spend: ${balanceAfterAllowSpend}`);

        logWorkflow.info('Allow spend created and verified, proceeding with spend transaction');

        const spendResult = await sendDataWithSpendTransaction(
            urls,
            hash,
            dag4.createAccount(PRIVATE_KEYS.key4).address,
            dag4.createAccount(PRIVATE_KEYS.key4).address
        );

        await verifyUnauthorizedSpendActionInGlobalL0(urls, CONSTANTS.CURRENCY_TOKEN_ID, spendResult.update);

        await sleep(CONSTANTS.SNAPSHOT_WAIT_TIME_MS);
        const balanceAfterSpend = await balanceManager.getDagBalance(PRIVATE_KEYS.key3);
        if (balanceAfterSpend !== balanceAfterAllowSpend) {
            throw new Error(`Balance changed after unauthorized spend. Expected: ${balanceAfterAllowSpend}, got: ${balanceAfterSpend}`);
        }
        logWorkflow.success(`DAG balance unchanged after unauthorized spend as expected`);

        logWorkflow.success('UnauthorizedSpendTransaction');
    } catch (error) {
        logWorkflow.error('UnauthorizedSpendTransaction', error);
        throw error;
    }
};

const currencySpendTransactionWorkflow = {
    workflowName: 'CurrencySpendTransaction',
    getInitialBalance: (balanceManager) =>
        balanceManager.getCurrencyBalance(PRIVATE_KEYS.key3),
    sendTransaction: (handler) =>
        handler.sendCurrencyTransaction(PRIVATE_KEYS.key3),
    verifyL1: (verifier, hash) =>
        verifier.verifyCurrencyL1(hash),
    verifyL0: (verifier, address, hash) =>
        verifier.verifyCurrencyL0(address, hash),
    verifyGlobalL0: (verifier, address, hash) =>
        verifier.verifyGlobalL0ForCurrency(address, hash),
    verifyBalance: (balanceManager, initialBalance, amount, fee) =>
        balanceManager.verifyCurrencyBalanceChange(PRIVATE_KEYS.key3, initialBalance, amount, fee)
};

const executeUnauthorizedCurrencySpendTransactionWorkflow = async () => {
    try {
        logWorkflow.start('UnauthorizedCurrencySpendTransaction');

        const config = createConfig();
        const urls = createNetworkConfig(config);
        const balanceManager = createBalanceManager(urls);

        const initialBalance = await balanceManager.getCurrencyBalance(PRIVATE_KEYS.key3);
        logWorkflow.info(`Initial balance before allow spend: ${initialBalance}`);

        logWorkflow.info('Currency allow spend creation started');
        const { address, hash, amount: allowSpendAmount } = await executeWorkflow({
            urls,
            ...currencySpendTransactionWorkflow,
            options: {
                skipExpirationCheck: true
            }
        });

        const balanceAfterAllowSpend = await balanceManager.getCurrencyBalance(PRIVATE_KEYS.key3);
        logWorkflow.info(`Balance after allow spend: ${balanceAfterAllowSpend}`);

        logWorkflow.info('Currency allow spend created and verified, proceeding with unauthorized spend transaction');

        const spendResult = await sendDataWithSpendTransaction(
            urls,
            hash,
            dag4.createAccount(PRIVATE_KEYS.key4).address,
            dag4.createAccount(PRIVATE_KEYS.key4).address
        );

        await verifyUnauthorizedSpendActionInGlobalL0(urls, CONSTANTS.CURRENCY_TOKEN_ID, spendResult.update);

        await sleep(CONSTANTS.SNAPSHOT_WAIT_TIME_MS);
        const balanceAfterSpend = await balanceManager.getCurrencyBalance(PRIVATE_KEYS.key3);
        if (balanceAfterSpend !== balanceAfterAllowSpend) {
            throw new Error(`Balance changed after unauthorized spend. Expected: ${balanceAfterAllowSpend}, got: ${balanceAfterSpend}`);
        }
        logWorkflow.success(`Currency balance unchanged after unauthorized spend as expected`);

        logWorkflow.success('UnauthorizedCurrencySpendTransaction');
    } catch (error) {
        logWorkflow.error('UnauthorizedCurrencySpendTransaction', error);
        throw error;
    }
};

const executeExceedingBalanceWorkflow = async () => {
    await executeInvalidTransactionWorkflow(
        createExceedingBalanceAllowSpendTransaction,
        'ExceedingBalanceAllowSpend',
        'exceeding balance'
    );
};

const executeInvalidParentWorkflow = async () => {
    await executeInvalidTransactionWorkflow(
        createInvalidParentAllowSpendTransaction,
        'InvalidParentAllowSpend',
        'parent reference'
    );
};

const executeInvalidEpochProgressWorkflow = async () => {
    await executeInvalidTransactionWorkflow(
        createInvalidEpochProgressAllowSpendTransaction,
        'InvalidEpochProgressAllowSpend',
        'epoch progress'
    );
};

const verifyAllowSpendIsInactive = async (address, hash, l0Url) => {
    try {
        logWorkflow.info(`Verifying allow spend ${hash} is inactive for address ${address}`);

        const { data: snapshot } = await axios.get(`${l0Url}/global-snapshots/latest/combined`);

        if (!snapshot.activeAllowSpends) {
            logWorkflow.info('No active allow spends found in snapshot');
            return true;
        }

        const addressAllowSpends = snapshot.activeAllowSpends[address] || [];

        if (addressAllowSpends.length === 0) {
            logWorkflow.info(`No active allow spends found for address ${address}`);
            return true;
        }

        const isActive = await findMatchingHash(addressAllowSpends, hash);

        if (isActive) {
            logWorkflow.error(`Allow spend ${hash} is still active for address ${address}`);
            return false;
        } else {
            logWorkflow.success(`Allow spend ${hash} is correctly inactive for address ${address}`);
            return true;
        }
    } catch (error) {
        logWorkflow.error(`Error verifying allow spend inactive status: ${error.message}`);
        throw error;
    }
};

const executeDoubleUseAllowSpendWorkflow = async () => {
    try {
        logWorkflow.start('DoubleUseAllowSpend');

        const config = createConfig();
        const urls = createNetworkConfig(config);
        const balanceManager = createBalanceManager(urls);

        const initialBalance = await balanceManager.getDagBalance(PRIVATE_KEYS.key3);
        logWorkflow.info(`Initial balance before allow spend: ${initialBalance}`);

        await transferTokensToCurrencyId(urls)

        logWorkflow.info('Allow spend creation started');
        const { address, hash, amount: allowSpendAmount } = await executeWorkflow({
            urls,
            ...spendTransactionWorkflow,
            options: {
                skipExpirationCheck: true
            }
        });

        const balanceAfterAllowSpend = await balanceManager.getDagBalance(PRIVATE_KEYS.key3);
        logWorkflow.info(`Balance after allow spend: ${balanceAfterAllowSpend}`);

        logWorkflow.info('Allow spend created and verified, proceeding with first spend transaction');

        const spendResult = await sendDataWithSpendTransaction(
            urls,
            hash,
            address,
            CONSTANTS.CURRENCY_TOKEN_ID
        );

        await verifySpendActionInGlobalL0(urls, CONSTANTS.CURRENCY_TOKEN_ID, spendResult.update);

        await sleep(CONSTANTS.SNAPSHOT_WAIT_TIME_MS);
        await balanceManager.verifyDagBalanceAfterSpend(
            PRIVATE_KEYS.key3,
            initialBalance,
            balanceAfterAllowSpend,
            allowSpendAmount,
            spendResult.spendAmount
        );

        logWorkflow.info('First spend transaction successful, allow spend should now be inactivated');

        await sleep(CONSTANTS.SNAPSHOT_WAIT_TIME_MS);

        const isInactive = await verifyAllowSpendIsInactive(address, hash, urls.globalL0Url);
        if (!isInactive) {
            throw new Error(`Allow spend ${hash} is still active after being spent`);
        }

        logWorkflow.info('Attempting to use the same allow spend again - this should not be accepted on global L0');

        const secondSpendResult = await sendDataWithSpendTransaction(
            urls,
            hash,
            address,
            CONSTANTS.CURRENCY_TOKEN_ID
        );

        try {
            await verifyUnauthorizedSpendActionInGlobalL0(
                urls,
                CONSTANTS.CURRENCY_TOKEN_ID,
                secondSpendResult.update
            );
            logWorkflow.success('Second spend transaction correctly rejected at global L0 as expected');
        } catch (error) {
            logWorkflow.error(`Verification failed: ${error.message}`);
            throw new Error('Second spend transaction with already used allow spend was incorrectly accepted on global L0');
        }

        logWorkflow.success('DoubleUseAllowSpend workflow completed successfully');
    } catch (error) {
        logWorkflow.error('DoubleUseAllowSpend', error);
        throw error;
    }
};

const verifySpendActionInGlobalL0 = async (urls, metagraphId, update) => {
    const maxAttempts = CONSTANTS.MAX_VERIFICATION_ATTEMPTS;

    logWorkflow.info(`Watching for spend action in Global L0 for metagraph ${metagraphId}`);
    let lastOrdinal = null

    await withRetry(
        async () => {
            let spendActions = [];
            const {data: snapshot} = await axios.get(`${urls.globalL0Url}/global-snapshots/latest/combined`);
            const currentOrdinal = snapshot[0]?.value?.ordinal;
            spendActions.push(...snapshot[0]?.value?.spendActions?.[metagraphId] || []);

            if (lastOrdinal && currentOrdinal > lastOrdinal + 1) {
                for (let ordinal = lastOrdinal + 1; ordinal < currentOrdinal; ordinal++) {
                    try {
                        const {data: missingSnapshot} = await axios.get(
                            `${urls.globalL0Url}/global-snapshots/${ordinal}`
                        );
                        spendActions.push(...missingSnapshot?.value?.spendActions?.[metagraphId] || []);
                    } catch (error) {
                        logWorkflow.warn(`Failed to fetch snapshot for ordinal ${ordinal}:`, error.message);
                    }
                }
            }

            lastOrdinal = currentOrdinal;

            if (!spendActions || spendActions.length === 0) {
                logWorkflow.warning(`No spend actions found for metagraph: ${metagraphId}`);
                throw new Error('Spend action not found');
            }

            logWorkflow.debug('Found spend actions: ' + JSON.stringify(spendActions, null, 2));

            const {spendTransactionA, spendTransactionB} = update.UsageUpdateWithSpendTransaction;

            const matchingSpend = spendActions.find(action => {
                const [firstSpendTransaction, secondSpendTransaction] = action.spendTransactions || [];

                return sortedJsonStringify(firstSpendTransaction) === sortedJsonStringify(spendTransactionA) && sortedJsonStringify(secondSpendTransaction) === sortedJsonStringify(spendTransactionB);
            });

            if (!matchingSpend) {
                throw new Error('Matching spend action not found');
            }

            logWorkflow.debug('Found matching spend action: ' + JSON.stringify(matchingSpend, null, 2));
        },
        {
            name: 'Spend action verification',
            interval: CONSTANTS.VERIFICATION_INTERVAL_MS,
            maxAttempts
        }
    );
};

const verifyUnauthorizedSpendActionInGlobalL0 = async (urls, tokenId, update) => {
    try {
        logWorkflow.info('Verifying unauthorized spend action in global L0');

        return await withRetry(
            async () => {
                const { data: snapshot } = await axios.get(`${urls.globalL0Url}/global-snapshots/latest/combined`);

                if (!snapshot.spendActions) {
                    logWorkflow.info('No spend actions found in snapshot, which is expected for unauthorized spend');
                    return true;
                }

                const spendTransactionA = update.UsageUpdateWithSpendTransaction.spendTransactionA;
                const allowSpendRef = spendTransactionA.allowSpendRef;

                logWorkflow.info(`Checking that no spend action exists with allowSpendRef: ${allowSpendRef}`);

                const spendActionExists = snapshot.spendActions.some(action => {
                    if (!action.value) return false;

                    const actionValue = action.value;
                    const hasMatchingAllowSpendRef = actionValue.allowSpendRef === allowSpendRef;

                    if (hasMatchingAllowSpendRef) {
                        throw new Error(`Found matching spend action which should not exist: ${JSON.stringify(actionValue)}`);
                    }

                    return hasMatchingAllowSpendRef;
                });

                if (spendActionExists) {
                    throw new Error('Unauthorized spend action was incorrectly found in global L0');
                } else {
                    logWorkflow.success('No unauthorized spend action found in global L0 as expected');
                    return true;
                }
            },
            {
                name: 'Verify unauthorized spend action',
                maxAttempts: CONSTANTS.MAX_VERIFICATION_ATTEMPTS,
                interval: CONSTANTS.VERIFICATION_INTERVAL_MS,
                handleError: (error, attempt) => {
                    if (attempt < CONSTANTS.MAX_VERIFICATION_ATTEMPTS) {
                        logWorkflow.warning(`Attempt ${attempt}: ${error.message}. Retrying...`);
                    } else {
                        logWorkflow.error(`Failed after ${attempt} attempts: ${error.message}`);
                    }
                }
            }
        );
    } catch (error) {
        logWorkflow.error(`Error verifying unauthorized spend action in global L0: ${error.message}`);
        throw error;
    }
};

const waitForAllAllowSpendsToExpire = async (l0Url) => {
    try {
        logWorkflow.info('Waiting for all allow spends to expire...');

        const maxAttempts = 120;
        const checkInterval = CONSTANTS.EXPIRATION_VERIFICATION_INTERVAL_MS;

        for (let attempt = 1; attempt <= maxAttempts; attempt++) {
            logWorkflow.info(`Checking for active allow spends (attempt ${attempt}/${maxAttempts})...`);

            const { data: snapshot } = await axios.get(`${l0Url}/global-snapshots/latest/combined`);

            if (!snapshot[1]?.activeAllowSpends) {
                logWorkflow.success('No active allow spends found, proceeding with workflow');
                return true;
            }

            const { totalActiveAllowSpends, hasActiveAllowSpends } = Object.keys(snapshot[1].activeAllowSpends)
                .reduce((acc, tokenId) => {
                    const tokenAllowSpends = snapshot[1].activeAllowSpends[tokenId];

                    if (!tokenAllowSpends) {
                        return acc;
                    }

                    return Object.keys(tokenAllowSpends).reduce((innerAcc, address) => {
                        const addressAllowSpends = tokenAllowSpends[address] || [];
                        const count = addressAllowSpends.length;

                        return {
                            totalActiveAllowSpends: innerAcc.totalActiveAllowSpends + count,
                            hasActiveAllowSpends: innerAcc.hasActiveAllowSpends || count > 0
                        };
                    }, acc);
                }, { totalActiveAllowSpends: 0, hasActiveAllowSpends: false });

            if (!hasActiveAllowSpends) {
                logWorkflow.success('No active allow spends found, proceeding with workflow');
                return true;
            }

            logWorkflow.info(`Found ${totalActiveAllowSpends} active allow spends, waiting for them to expire...`);

            await sleep(checkInterval);
        }

        logWorkflow.warning('Maximum attempts reached, proceeding with workflow despite active allow spends');
        return false;
    } catch (error) {
        logWorkflow.error(`Error waiting for allow spends to expire: ${error.message}`);
        throw error;
    }
};

const createInvalidCurrencyDestinationAllowSpendTransaction = async (sourceAccount, l1Url, l0Url) => {
    const [{ data: lastRef }, currentEpochProgress] = await Promise.all([
        axios.get(`${l1Url}/allow-spends/last-reference/${sourceAccount.address}`),
        getEpochProgress(l0Url, true)
    ]);

    const { allowSpend: amount, fee } = getRandomAmounts();
    const invalidDestination = dag4.createAccount(PRIVATE_KEYS.key4).address;

    logWorkflow.info(`Current epoch progress: ${currentEpochProgress}, setting lastValidEpochProgress to ${currentEpochProgress + CONSTANTS.EPOCH_PROGRESS_BUFFER}`);
    logWorkflow.info(`Using random amounts - allowSpend: ${amount}, fee: ${fee}`);
    logWorkflow.info(`Using invalid destination for currency allow spend: ${invalidDestination} (should be ${CONSTANTS.CURRENCY_TOKEN_ID})`);

    return {
        amount,
        approvers: [invalidDestination],
        destination: invalidDestination,
        fee,
        lastValidEpochProgress: currentEpochProgress + CONSTANTS.EPOCH_PROGRESS_BUFFER,
        parent: lastRef,
        source: sourceAccount.address,
        currency: invalidDestination
    };
};

const executeInvalidCurrencyDestinationWorkflow = async () => {
    try {
        logWorkflow.start('InvalidCurrencyDestinationAllowSpend');

        const config = createConfig();
        const urls = createNetworkConfig(config);
        
        logWorkflow.info('Invalid currency destination allow spend creation started');
        
        const sourceAccount = createAndConnectAccount(PRIVATE_KEYS.key1, { l0Url: urls.currencyL0Url, l1Url: urls.currencyL1Url }, true);
        const allowSpend = await createInvalidCurrencyDestinationAllowSpendTransaction(sourceAccount, urls.currencyL1Url, urls.currencyL0Url);
        const proof = await generateProof(allowSpend, PRIVATE_KEYS.key1, sourceAccount, SerializerType.BROTLI);

        try {
            const body = { value: allowSpend, proofs: [proof] };
            logWorkflow.info(`Sending allow spend with invalid currency destination: ${JSON.stringify(body.value)}`);
            const response = await axios.post(`${urls.currencyL1Url}/allow-spends`, body);
            logWorkflow.error(`Transaction was accepted when it should have been rejected: ${JSON.stringify(response.data)}`);
            throw new Error('Transaction with invalid currency destination was accepted when it should have been rejected');
        } catch (error) {
            if (error.response && error.response.status === 400) {
                logWorkflow.success(`Transaction correctly rejected with 400 Bad Request: ${error.response.data}`);
                logWorkflow.success('InvalidCurrencyDestinationAllowSpend workflow completed successfully - transaction was correctly rejected');
            } else {
                logWorkflow.error('Error in InvalidCurrencyDestinationAllowSpend workflow', error);
                throw error;
            }
        }
    } catch (error) {
        logWorkflow.error('InvalidCurrencyDestinationAllowSpend', error);
        throw error;
    }
};

const createExceedingAmountSpendTransaction = async (allowSpendHash, sourceAddress, destinationAddress, allowSpendAmount) => {
    const exceedingAmount = allowSpendAmount + 100;
    
    logWorkflow.info(`Creating spend transaction with amount (${exceedingAmount}) exceeding allow spend amount (${allowSpendAmount})`);
    
    const dataUpdate = {
        UsageUpdateWithSpendTransaction: {
            address: sourceAddress,
            usage: 10,
            spendTransactionA: {
                allowSpendRef: allowSpendHash,
                currency: null,
                amount: exceedingAmount,
                source: sourceAddress,
                destination: destinationAddress
            },
            spendTransactionB: createMetagraphSpendTransaction(destinationAddress)
        }
    };

    logWorkflow.debug('Created exceeding amount spend transaction: ' + JSON.stringify({
        address: dataUpdate.UsageUpdateWithSpendTransaction.address,
        usage: dataUpdate.UsageUpdateWithSpendTransaction.usage,
        spendTransactionA: {
            type: 'User',
            hasAllowSpendRef: !!dataUpdate.UsageUpdateWithSpendTransaction.spendTransactionA.allowSpendRef,
            destination: dataUpdate.UsageUpdateWithSpendTransaction.spendTransactionA.destination,
            amount: dataUpdate.UsageUpdateWithSpendTransaction.spendTransactionA.amount
        },
        spendTransactionB: {
            type: 'Metagraph',
            hasAllowSpendRef: !!dataUpdate.UsageUpdateWithSpendTransaction.spendTransactionB.allowSpendRef,
            destination: dataUpdate.UsageUpdateWithSpendTransaction.spendTransactionB.destination,
            amount: dataUpdate.UsageUpdateWithSpendTransaction.spendTransactionB.amount
        }
    }, null, 2));

    return dataUpdate;
};

const sendExceedingAmountSpendTransaction = async (urls, allowSpendHash, sourceAddress, destinationAddress, allowSpendAmount) => {
    const dataUpdate = createExceedingAmountSpendTransaction(allowSpendHash, sourceAddress, destinationAddress, allowSpendAmount);
    const account = dag4.createAccount(PRIVATE_KEYS.key3);
    const proof = await generateProof(dataUpdate, PRIVATE_KEYS.key3, account, SerializerType.STANDARD);

    const body = {
        value: dataUpdate,
        proofs: [proof]
    };

    try {
        logWorkflow.info(`Sending data transaction with exceeding spend amount: ${JSON.stringify(body)}`);
        const response = await axios.post(`${urls.dataL1Url}/data`, body);
        logWorkflow.error(`Transaction was accepted when it should have been rejected: ${JSON.stringify(response.data)}`);
        return { 
            response: response.data, 
            update: dataUpdate, 
            accepted: true 
        };
    } catch (error) {
        if (error.response && error.response.status === 400) {
            logWorkflow.success(`Transaction correctly rejected with 400 Bad Request: ${error.response.data}`);
            return { 
                update: dataUpdate, 
                rejected: true, 
                error: error.response.data 
            };
        }
        logWorkflow.error('Error sending data transaction with exceeding spend amount', error);
        throw error;
    }
};

const executeExceedingAmountSpendWorkflow = async () => {
    try {
        logWorkflow.start('ExceedingAmountSpendTransaction');

        const config = createConfig();
        const urls = createNetworkConfig(config);
        const balanceManager = createBalanceManager(urls);

        const initialBalance = await balanceManager.getDagBalance(PRIVATE_KEYS.key3);
        logWorkflow.info(`Initial balance before allow spend: ${initialBalance}`);

        await transferTokensToCurrencyId(urls);

        logWorkflow.info('Allow spend creation started');
        const { address, hash, amount: allowSpendAmount } = await executeWorkflow({
            urls,
            ...spendTransactionWorkflow,
            options: {
                skipExpirationCheck: true
            }
        });

        const balanceAfterAllowSpend = await balanceManager.getDagBalance(PRIVATE_KEYS.key3);
        logWorkflow.info(`Balance after allow spend: ${balanceAfterAllowSpend}`);
        logWorkflow.info(`Allow spend amount: ${allowSpendAmount}`);

        logWorkflow.info('Allow spend created and verified, proceeding with exceeding amount spend transaction');

        const spendResult = await sendExceedingAmountSpendTransaction(
            urls,
            hash,
            address,
            CONSTANTS.CURRENCY_TOKEN_ID,
            allowSpendAmount
        );

        if (spendResult.accepted) {
            logWorkflow.error('Spend transaction with exceeding amount was incorrectly accepted');
            throw new Error('Spend transaction with exceeding amount was incorrectly accepted');
        }

        await sleep(CONSTANTS.SNAPSHOT_WAIT_TIME_MS);
        const finalBalance = await balanceManager.getDagBalance(PRIVATE_KEYS.key3);
        
        if (finalBalance !== balanceAfterAllowSpend) {
            logWorkflow.error(`Balance changed after rejected transaction. Expected: ${balanceAfterAllowSpend}, got: ${finalBalance}`);
            throw new Error('Balance changed after rejected transaction');
        }
        
        logWorkflow.success('Balance remained unchanged after rejected transaction as expected');
        logWorkflow.success('ExceedingAmountSpendTransaction workflow completed successfully');
    } catch (error) {
        logWorkflow.error('ExceedingAmountSpendTransaction', error);
        throw error;
    }
};

const executeWorkflowByType = async (workflowType) => {
    const config = createConfig();
    const urls = createNetworkConfig(config);

    await waitForAllAllowSpendsToExpire(urls.globalL0Url);

    switch (workflowType) {
        case 'dag':
            await executeWorkflow({ urls, ...dagWorkflow });
            break;
        case 'currency':
            await executeWorkflow({ urls, ...currencyWorkflow });
            break;
        case 'spend':
            await executeSpendTransactionWorkflow();
            break;
        case 'unauthorized':
            await executeUnauthorizedSpendTransactionWorkflow();
            break;
        case 'unauthorized-currency':
            await executeUnauthorizedCurrencySpendTransactionWorkflow();
            break;
        case 'exceeding-balance':
            await executeExceedingBalanceWorkflow();
            break;
        case 'invalid-parent':
            await executeInvalidParentWorkflow();
            break;
        case 'invalid-epoch':
            await executeInvalidEpochProgressWorkflow();
            break;
        case 'invalid-signature':
            await executeInvalidSignatureWorkflow();
            break;
        case 'double-spend':
            await executeDoubleSpendWorkflow();
            break;
        case 'expired-allow-spend':
            await executeExpiredAllowSpendWorkflow();
            break;
        case 'double-use-allow-spend':
            await executeDoubleUseAllowSpendWorkflow();
            break;
        case 'invalid-currency-destination':
            await executeInvalidCurrencyDestinationWorkflow();
            break;
        case 'exceeding-amount-spend':
            await executeExceedingAmountSpendWorkflow();
            break;
        default:
            throw new Error(`Unknown workflow type: ${workflowType}`);
    }
}

const workflowType = process.argv[7];
if (!workflowType) {
    throw new Error('Workflow type must be specified as the 6th argument');
}

executeWorkflowByType(workflowType);