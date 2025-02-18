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
    sortedJsonStringify
} = require('../shared');
const CONSTANTS = {
    ...sharedConstants,
    EPOCH_PROGRESS_BUFFER: 5,
    TRANSACTION_FEE: 1
};

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

    console.log(`Current epoch progress: ${currentEpochProgress}, setting lastValidEpochProgress to ${currentEpochProgress + CONSTANTS.EPOCH_PROGRESS_BUFFER}`);

    return {
        amount: 100,
        approvers: [ammAddress],
        destination: ammAddress,
        fee: CONSTANTS.TRANSACTION_FEE,
        lastValidEpochProgress: currentEpochProgress + CONSTANTS.EPOCH_PROGRESS_BUFFER,
        parent: lastRef,
        source: sourceAccount.address
    };
};

const createVerifier = (urls) => {
    const findMatchingHash = async (allowSpends, targetHash) => {
        console.log('Searching for hash:', targetHash);
        
        const allowSpendHashes = await Promise.all(allowSpends.map(async spend => {
            if (!spend?.value) {
                console.log('Invalid allow spend:', spend);
                return null;
            }
            const serializer = createSerializer(SerializerType.BROTLI);
            const message = await serializer.serialize(spend.value);
            return {
                hash: jsSha256.sha256(Buffer.from(message, 'hex')),
                value: spend.value
            };
        })).then(hashes => hashes.filter(Boolean));
        
        console.log('Active allow spends:', allowSpendHashes);
        
        return allowSpends.reduce(async (acc, allowSpend) => {
            const prevResult = await acc;
            if (prevResult) return true;
            
            if (!allowSpend?.value) {
                console.log('Skipping invalid allow spend:', allowSpend);
                return false;
            }

            const serializer = createSerializer(SerializerType.BROTLI);
            const message = await serializer.serialize(allowSpend.value);
            const allowSpendHash = jsSha256.sha256(Buffer.from(message, 'hex'));
            console.log('Comparing hashes:', {
                target: targetHash,
                current: allowSpendHash,
                matches: allowSpendHash === targetHash
            });
            return allowSpendHash === targetHash;
        }, Promise.resolve(false));
    };

    const verifyInL1 = async (hash, l1Url, layerName) => {
        await withRetry(
            async () => {
                console.log(`Checking ${layerName} L1 for hash:`, hash);
                const response = await axios.get(`${l1Url}/allow-spends/${hash}`);
                if (!response.data) {
                    console.log(`No data found in ${layerName} L1 for hash:`, hash);
                    throw new Error('Transaction not found');
                }
                console.log(`Found allow spend in ${layerName} L1:`, response.data);
                console.log(`AllowSpend transaction processed successfully in ${layerName} L1`);
            },
            { name: `${layerName} L1 verification` }
        );
    };

    const verifyAllowSpendInSnapshot = async (address, hash, snapshot, tokenId, layerName, isCurrency = false) => {
        console.log(`Verifying allow spend in ${layerName} snapshot for address:`, address);

        const activeAllowSpendsForAddress = isCurrency
            ? snapshot[1]?.activeAllowSpends?.[address]
            : snapshot[1]?.activeAllowSpends?.[tokenId]?.[address];

        if (!activeAllowSpendsForAddress) {
            console.log(`No active allow spends found for address ${address} in ${layerName}. Full snapshot:`, snapshot[1]?.activeAllowSpends);
            throw new Error(`No active allow spends found for address ${address} in ${layerName}`);
        }

        console.log(`Found ${activeAllowSpendsForAddress.length} active allow spends for address ${address}`);
        
        const hasMatchingHash = await findMatchingHash(activeAllowSpendsForAddress, hash);
        if (!hasMatchingHash) {
            console.log(`Allow spend with hash ${hash} not found in ${layerName}. Available hashes:`, 
                await Promise.all(activeAllowSpendsForAddress.map(async spend => {
                    const serializer = createSerializer(SerializerType.BROTLI);
                    const message = await serializer.serialize(spend.value);
                    return {
                        hash: jsSha256.sha256(Buffer.from(message, 'hex')),
                        value: spend.value
                    };
                }))
            );
            throw new Error(`Allow spend with hash ${hash} not found in ${layerName}`);
        }

        console.log(`AllowSpend transaction found in ${layerName}`);
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
                console.log(`${layerName} balance verified successfully`);
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

    const sendTransaction = async (sourcePrivateKey, destinationPrivateKey, l0Url, l1Url, isCurrency = false) => {
        const sourceAccount = createAndConnectAccount(sourcePrivateKey, { l0Url, l1Url }, isCurrency);
        const ammAddress = dag4.createAccount(destinationPrivateKey).address;

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
        sendDagTransaction: (sourcePrivateKey, destinationPrivateKey) =>
            sendTransaction(sourcePrivateKey, destinationPrivateKey, urls.globalL0Url, urls.dagL1Url, false),
        sendCurrencyTransaction: (sourcePrivateKey, destinationPrivateKey) =>
            sendTransaction(sourcePrivateKey, destinationPrivateKey, urls.currencyL0Url, urls.currencyL1Url, true)
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
            console.log(`Epoch progress advanced past ${lastValidEpochProgress}`);
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

    console.log(`Allow spend expired and balance reverted successfully (minus fee) in ${isCurrency ? 'Currency' : 'DAG'}`);
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
    skipExpirationCheck = false
    }) => {
    const balanceManager = createBalanceManager(urls);
    const transactionHandler = createTransactionHandler(urls);
    const verifier = createVerifier(urls);

    console.log(`Starting ${workflowName} workflow`);

    const initialBalance = await getInitialBalance(balanceManager);

    const { address, hash, amount, fee, lastValidEpochProgress } = await sendTransaction(transactionHandler);

    console.log(`Allow spend transaction sent successfully with lastValidEpochProgress: ${lastValidEpochProgress}`);

    await verifyL1(verifier, hash);
    await sleep(CONSTANTS.SNAPSHOT_WAIT_TIME_MS);
    await verifyL0(verifier, address, hash);

    if (verifyGlobalL0) {
        await verifyGlobalL0(verifier, address, hash);
    }

    await verifyBalance(balanceManager, initialBalance, amount, fee);

    console.log(`${workflowName} workflow completed successfully, waiting for expiration...`);

    if (!skipExpirationCheck) {
        await verifyAllowSpendExpiration(
            address, 
            hash, 
            initialBalance,
            urls,
            lastValidEpochProgress,
            fee,
            workflowName === 'Currency'
        );
        
        console.log(`${workflowName} workflow expiration verified successfully`);
    }

    return { address, hash };
};

const dagWorkflow = {
    workflowName: 'DAG',
    getInitialBalance: (balanceManager) =>
        balanceManager.getDagBalance(PRIVATE_KEYS.key1),
    sendTransaction: (handler) =>
        handler.sendDagTransaction(PRIVATE_KEYS.key1, PRIVATE_KEYS.key2),
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
        handler.sendCurrencyTransaction(PRIVATE_KEYS.key1, PRIVATE_KEYS.key2),
    verifyL1: (verifier, hash) =>
        verifier.verifyCurrencyL1(hash),
    verifyL0: (verifier, address, hash) =>
        verifier.verifyCurrencyL0(address, hash),
    verifyGlobalL0: (verifier, address, hash) =>
        verifier.verifyGlobalL0ForCurrency(address, hash),
    verifyBalance: (balanceManager, initialBalance, amount, fee) =>
        balanceManager.verifyCurrencyBalanceChange(PRIVATE_KEYS.key1, initialBalance, amount, fee)
};

const createUserSpendTransaction = (allowSpendRef, address, amount) => {
    const tx = {
        allowSpendRef: allowSpendRef,
        currency: null,
        amount: amount,
        destination: address
    };
    console.log('Created user spend transaction:', {
        type: 'User',
        allowSpendRef,
        destination: address,
        amount
    });
    return tx;
};

const createMetagraphSpendTransaction = (address, amount) => {
    const tx = {
        allowSpendRef: null,
        currency: null,
        amount: amount,
        destination: address
    };
    console.log('Created metagraph spend transaction:', {
        type: 'Metagraph',
        destination: address,
        amount
    });
    return tx;
};

const createUsageUpdateWithSpendTransaction = (address, allowSpendRef, destinationAddress) => {
    console.log('Creating usage update with spend transactions:', {
        sourceAddress: address,
        allowSpendRef,
        destinationAddress
    });
    
    const update = {
        UsageUpdateWithSpendTransaction: {
            address: address,
            usage: 10,
            spendTransactionA: createUserSpendTransaction(allowSpendRef, destinationAddress, 100),
            spendTransactionB: createMetagraphSpendTransaction(destinationAddress, 100)
        }
    };
    
    console.log('Created complete usage update:', {
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
    });
    
    return update;
};

const spendTransactionWorkflow = {
    workflowName: 'SpendTransaction',
    getInitialBalance: (balanceManager) => 
        balanceManager.getDagBalance(PRIVATE_KEYS.key3),
    sendTransaction: (handler) => 
        handler.sendDagTransaction(PRIVATE_KEYS.key3, PRIVATE_KEYS.key4),
    verifyL1: (verifier, hash) => 
        verifier.verifyDagL1(hash),
    verifyL0: (verifier, address, hash) => 
        verifier.verifyDagL0(address, hash),
    verifyBalance: (balanceManager, initialBalance, amount, fee) =>
        balanceManager.verifyDagBalanceChange(PRIVATE_KEYS.key3, initialBalance, amount, fee)
};

const verifySpendActionInGlobalL0 = async (urls, metagraphId, update) => {
    const maxAttempts = CONSTANTS.MAX_VERIFICATION_ATTEMPTS;
    
    console.log(`Watching for spend action in Global L0 for metagraph ${metagraphId}`);
    
    await withRetry(
        async () => {
            const { data: snapshot } = await axios.get(`${urls.globalL0Url}/global-snapshots/latest/combined`);
            const spendActions = snapshot[0]?.value?.spendActions?.[metagraphId];
            
            if (!spendActions || spendActions.length === 0) {
                console.log('No spend actions found for metagraph:', metagraphId);
                throw new Error('Spend action not found');
            }

            console.log('Found spend actions:', spendActions);

            const { spendTransactionA, spendTransactionB } = update.UsageUpdateWithSpendTransaction;
            
            const matchingSpend = spendActions.find(action => {
                const { input, output } = action;

                return sortedJsonStringify(input) === sortedJsonStringify(spendTransactionA) && sortedJsonStringify(output) === sortedJsonStringify(spendTransactionB);
            });

            if (!matchingSpend) {
                throw new Error('Matching spend action not found');
            }

            console.log('Found matching spend action:', matchingSpend);
        },
        {
            name: 'Spend action verification',
            interval: CONSTANTS.VERIFICATION_INTERVAL_MS,
            maxAttempts
        }
    );
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
        console.log(`Sending data transaction with spend: ${JSON.stringify(body)}`);
        const response = await axios.post(`${urls.dataL1Url}/data`, body);
        console.log(`Response: ${JSON.stringify(response.data)}`);
        
        return { response: response.data, update: dataUpdate };
    } catch (e) {
        console.error('Error sending data transaction with spend', e);
        throw e;
    }
};

const executeSpendTransactionWorkflow = async () => {
    const config = createConfig();
    const urls = createNetworkConfig(config);
    
    console.log('Starting SpendTransaction workflow');
    
    const { address, hash } = await executeWorkflow({ 
        urls, 
        ...spendTransactionWorkflow,
        skipExpirationCheck: true
    });

    console.log('Allow spend created and verified, proceeding with spend transaction');
    
    const spendResult = await sendDataWithSpendTransaction(
        urls,
        hash,
        address,
        dag4.createAccount(PRIVATE_KEYS.key4).address
    );

    await verifySpendActionInGlobalL0(urls, CONSTANTS.CURRENCY_TOKEN_ID, spendResult.update);
    
    console.log('SpendTransaction workflow completed');
};

const executeAllWorkflows = async () => {
    const config = createConfig();
    const urls = createNetworkConfig(config);

    await executeWorkflow({ urls, ...dagWorkflow });
    await executeWorkflow({ urls, ...currencyWorkflow });
    await executeSpendTransactionWorkflow();
};

executeAllWorkflows();