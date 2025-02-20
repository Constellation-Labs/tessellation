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
        source: sourceAccount.address
    };
};

const createVerifier = (urls) => {
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
            )
    };
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

    return {
        sendDagTransaction: (sourcePrivateKey) =>
            sendTransaction(sourcePrivateKey, urls.globalL0Url, urls.dagL1Url, false),
        sendCurrencyTransaction: (sourcePrivateKey) =>
            sendTransaction(sourcePrivateKey, urls.currencyL0Url, urls.currencyL1Url, true)
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

    return { address, hash };
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

const createUserSpendTransaction = (allowSpendRef, address) => {
    const { spend: amount } = getRandomAmounts();
    const tx = {
        allowSpendRef: allowSpendRef,
        currency: null,
        amount,
        destination: address
    };
    logWorkflow.info('Created user spend transaction: ' + JSON.stringify({
        type: 'User',
        allowSpendRef,
        destination: address,
        amount
    }, null, 2));
    return tx;
};

const createMetagraphSpendTransaction = (address) => {
    const { spend: amount } = getRandomAmounts();
    const tx = {
        allowSpendRef: null,
        currency: null,
        amount,
        destination: address
    };
    logWorkflow.info('Created metagraph spend transaction: ' + JSON.stringify({
        type: 'Metagraph',
        destination: address,
        amount
    }, null, 2));
    return tx;
};

const createUsageUpdateWithSpendTransaction = (address, allowSpendRef, destinationAddress) => {
    logWorkflow.info('Creating usage update with spend transactions: ' + JSON.stringify({
        sourceAddress: address,
        allowSpendRef,
        destinationAddress
    }, null, 2));
    
    const update = {
        UsageUpdateWithSpendTransaction: {
            address: address,
            usage: 10,
            spendTransactionA: createUserSpendTransaction(allowSpendRef, address),
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

const verifySpendActionInGlobalL0 = async (urls, metagraphId, update) => {
    const maxAttempts = CONSTANTS.MAX_VERIFICATION_ATTEMPTS;
    
    logWorkflow.info(`Watching for spend action in Global L0 for metagraph ${metagraphId}`);
    
    await withRetry(
        async () => {
            const { data: snapshot } = await axios.get(`${urls.globalL0Url}/global-snapshots/latest/combined`);
            const spendActions = snapshot[0]?.value?.spendActions?.[metagraphId];
            
            if (!spendActions || spendActions.length === 0) {
                logWorkflow.warning(`No spend actions found for metagraph: ${metagraphId}`);
                throw new Error('Spend action not found');
            }

            logWorkflow.debug('Found spend actions: ' + JSON.stringify(spendActions, null, 2));

            const { spendTransactionA, spendTransactionB } = update.UsageUpdateWithSpendTransaction;
            
            const matchingSpend = spendActions.find(action => {
                const { input, output } = action;

                return sortedJsonStringify(input) === sortedJsonStringify(spendTransactionA) && sortedJsonStringify(output) === sortedJsonStringify(spendTransactionB);
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

const verifyUnauthorizedSpendActionInGlobalL0 = async (urls, metagraphId, update) => {
    const maxAttempts = CONSTANTS.MAX_VERIFICATION_ATTEMPTS;
    
    logWorkflow.info(`Verifying unauthorized spend action is NOT in Global L0 for metagraph ${metagraphId}`);
    
    let attempts = 0;
    while (attempts < maxAttempts) {
        attempts++;
        try {
            const { data: snapshot } = await axios.get(`${urls.globalL0Url}/global-snapshots/latest/combined`);
            const spendActions = snapshot[0]?.value?.spendActions?.[metagraphId] || [];
            
            logWorkflow.debug('Found spend actions: ' + JSON.stringify(spendActions, null, 2));

            const { spendTransactionA, spendTransactionB } = update.UsageUpdateWithSpendTransaction;
            
            const matchingSpend = spendActions.find(action => {
                const { input, output } = action;
                return sortedJsonStringify(input) === sortedJsonStringify(spendTransactionA) && 
                       sortedJsonStringify(output) === sortedJsonStringify(spendTransactionB);
            });

            if (matchingSpend) {
                logWorkflow.warning('Found unauthorized spend action when it should not exist: ' + JSON.stringify(matchingSpend, null, 2));
                throw new Error('Unauthorized spend action was found when it should not exist');
            }

            if (attempts < maxAttempts) {
                await sleep(CONSTANTS.VERIFICATION_INTERVAL_MS);
                continue;
            }

            logWorkflow.success('Verified: No unauthorized spend action found after all attempts, as expected');
            return;
        } catch (error) {
            if (attempts >= maxAttempts || error.message.includes('Unauthorized spend action was found')) {
                throw error;
            }
            await sleep(CONSTANTS.VERIFICATION_INTERVAL_MS);
        }
    }
};

const sendDataWithSpendTransaction = async (urls, allowSpendHash, sourceAddress, destinationAddress) => {
    const dataUpdate = createUsageUpdateWithSpendTransaction(sourceAddress, allowSpendHash, destinationAddress);
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
        
        return { response: response.data, update: dataUpdate };
    } catch (e) {
        logWorkflow.error('Error sending data transaction with spend', e);
        throw e;
    }
};

const executeSpendTransactionWorkflow = async () => {
    try {
        logWorkflow.start('SpendTransaction');
        
        const config = createConfig();
        const urls = createNetworkConfig(config);
        
        logWorkflow.info('Allow spend creation started');
        const { address, hash } = await executeWorkflow({ 
            urls, 
            ...spendTransactionWorkflow,
            options: {
                skipExpirationCheck: true
            }
        });

        logWorkflow.info('Allow spend created and verified, proceeding with spend transaction');
        
        const spendResult = await sendDataWithSpendTransaction(
            urls,
            hash,
            address,
            dag4.createAccount(PRIVATE_KEYS.key4).address
        );

        await verifySpendActionInGlobalL0(urls, CONSTANTS.CURRENCY_TOKEN_ID, spendResult.update);
        
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
        
        logWorkflow.info('Allow spend creation started');
        const { address, hash } = await executeWorkflow({ 
            urls, 
            ...spendTransactionWorkflow,
            options: {
                skipExpirationCheck: true
            }
        });

        logWorkflow.info('Allow spend created and verified, proceeding with spend transaction');
        
        const spendResult = await sendDataWithSpendTransaction(
            urls,
            hash,
            dag4.createAccount(PRIVATE_KEYS.key4).address,
            dag4.createAccount(PRIVATE_KEYS.key4).address
        );

        await verifyUnauthorizedSpendActionInGlobalL0(urls, CONSTANTS.CURRENCY_TOKEN_ID, spendResult.update);
        
        logWorkflow.success('UnauthorizedSpendTransaction');
    } catch (error) {
        logWorkflow.error('UnauthorizedSpendTransaction', error);
        throw error;
    }
};

const executeWorkflowByType = async (workflowType) => {
    const config = createConfig();
    const urls = createNetworkConfig(config);

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
        default:
            throw new Error(`Unknown workflow type: ${workflowType}`);
    }
}

const workflowType = process.argv[7];
if (!workflowType) {
    throw new Error('Workflow type must be specified as the 6th argument');
}

executeWorkflowByType(workflowType);