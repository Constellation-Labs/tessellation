const { dag4 } = require('@stardust-collective/dag4');
const jsSha256 = require('js-sha256');
const axios = require('axios');
const {
    parseSharedArgs,
    CONSTANTS: sharedConstants,
    PRIVATE_KEYS,
    generateProof,
    sleep,
    withRetry,
    createAndConnectAccount,
    createNetworkConfig,
    getEpochProgress,
    SerializerType,
    createSerializer
} = require('../shared');

const CONSTANTS = {
    ...sharedConstants,
    EPOCH_PROGRESS_BUFFER_EXPIRATION_TEST: 5,
    EPOCH_PROGRESS_BUFFER_TOKEN_UNLOCK_TEST: 50
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

const createTokenLockTransaction = async (sourceAccount, l1Url, l0Url, epochProgressOffset) => {
    const [{ data: lastRef }, currentEpochProgress] = await Promise.all([
        axios.get(`${l1Url}/token-locks/last-reference/${sourceAccount.address}`),
        getEpochProgress(l0Url, true)
    ]);

    console.log(`Current epoch progress: ${currentEpochProgress}, setting unlockEpoch to ${currentEpochProgress + epochProgressOffset}`);

    return {
        amount: 100,
        currencyId: CONSTANTS.CURRENCY_TOKEN_ID,
        fee: 0,
        parent: lastRef,
        source: sourceAccount.address,
        unlockEpoch: currentEpochProgress + epochProgressOffset,
    };
};

const createUpdateWithTokenUnlockTransaction = async (sourceAccount, tokenLockHash) => {
    return {
        UsageUpdateWithTokenUnlock: {
            address: sourceAccount.address,
            currencyId: CONSTANTS.CURRENCY_TOKEN_ID,
            tokenLockRef: tokenLockHash,
            unlockAmount: 100,
            usage: 10
        }
    }
};

const createVerifier = (urls) => {
    const findMatchingHash = async (tokenLocks, targetHash) => {
        return tokenLocks.reduce(async (acc, tokenLock) => {
            const prevResult = await acc;
            if (prevResult) return true;

            const serializer = createSerializer(SerializerType.BROTLI);
            const message = await serializer.serialize(tokenLock.value);
            const tokenLockHash = jsSha256.sha256(Buffer.from(message, 'hex'));
            return tokenLockHash === targetHash;
        }, Promise.resolve(false));
    };

    const verifyInL1 = async (hash, l1Url, layerName) => {
        await withRetry(
            async () => {
                const response = await axios.get(`${l1Url}/token-locks/${hash}`);
                if (!response.data) {
                    throw new Error('Transaction not found');
                }
                console.log(`TokenLock transaction processed successfully in ${layerName} L1`);
            },
            { name: `${layerName} L1 verification` }
        );
    };

    const verifyTokenLockInSnapshot = async (address, hash, snapshot, tokenId, layerName, isCurrency = false) => {
        const activeTokenLocksForAddress = isCurrency
            ? snapshot[1]?.activeTokenLocks?.[address]
            : snapshot[1]?.lastCurrencySnapshots?.[tokenId]?.Right[1].activeTokenLocks?.[address];

        if (!activeTokenLocksForAddress) {
            throw new Error(`No active token locks found for address ${address} in ${layerName}`);
        }

        const hasMatchingHash = await findMatchingHash(activeTokenLocksForAddress, hash);
        if (!hasMatchingHash) {
            throw new Error(`Token lock with hash ${hash} not found in ${layerName}`);
        }

        console.log(`TokenLock transaction found in ${layerName}`);
    };

    const verifyInL0 = async (address, hash, l0Url, tokenId, layerName, isCurrency = false) => {
        await withRetry(
            async () => {
                const { data: snapshot } = await axios.get(
                    `${l0Url}/global-snapshots/latest/combined`
                );
                await verifyTokenLockInSnapshot(address, hash, snapshot, tokenId, `${layerName} L0`, isCurrency);
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
                await verifyTokenLockInSnapshot(
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
        verifyCurrencyL1: (hash) => verifyInL1(hash, urls.currencyL1Url, 'Currency'),
        verifyCurrencyL0: (address, hash) => verifyInCurrencyL0(address, hash),
        verifyGlobalL0ForCurrency: (address, hash) =>
            verifyInL0(address, hash, urls.globalL0Url, CONSTANTS.CURRENCY_TOKEN_ID, 'Global', false)
    };
};

const createBalanceManager = (urls) => {
    const getBalance = async (privateKey, l0Url) => {
        const account = dag4.createAccount(privateKey);
        const address = account.address;

        try {
            const snapshotUrl = `${l0Url}/snapshots/latest/combined`

            const { data: snapshot } = await axios.get(snapshotUrl);

            const balance = snapshot[1]?.balances?.[address] || 0;

            return balance;
        } catch (error) {
            console.error(`Error fetching balance from ${l0Url}:`, error.message);
            throw error;
        }
    };

    const verifyBalanceChange = async (privateKey, initialBalance, amount, l0Url, layerName) => {
        await withRetry(
            async () => {
                const currentBalance = await getBalance(privateKey, l0Url);
                const expectedBalance = initialBalance - amount;

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
        getCurrencyBalance: (privateKey) =>
            getBalance(privateKey, urls.currencyL0Url),
        verifyCurrencyBalanceChange: (privateKey, initialBalance, amount) =>
            verifyBalanceChange(
                privateKey,
                initialBalance,
                amount,
                urls.currencyL0Url,
                "Currency"
            )
    };
};

const createTokenLockTransactionHandler = (urls) => {
    const submitTransaction = async (tokenLock, proof, l1Url) => {
        try {
            const body = { value: tokenLock, proofs: [proof] };
            console.log(`TokenLockBody: ${JSON.stringify(body)}`)
            console.log(`Url: ${l1Url}/token-locks`)
            return await axios.post(`${l1Url}/token-locks`, body);
        } catch (error) {
            console.error('Error sending TokenLock transaction');
            throw error;
        }
    };

    const sendTransaction = async (sourcePrivateKey, l0Url, l1Url, epochProgressOffset) => {
        const sourceAccount = createAndConnectAccount(sourcePrivateKey, { l0Url, l1Url });

        const tokenLock = await createTokenLockTransaction(sourceAccount, l1Url, l0Url, epochProgressOffset);
        const proof = await generateProof(tokenLock, sourcePrivateKey, sourceAccount, SerializerType.BROTLI);

        const response = await submitTransaction(tokenLock, proof, l1Url);

        return {
            address: sourceAccount.address,
            hash: response.data.hash,
            amount: tokenLock.amount,
            unlockEpoch: tokenLock.unlockEpoch
        };
    };

    return {
        sendCurrencyTransaction: (sourcePrivateKey, epochProgressOffset) =>
            sendTransaction(sourcePrivateKey, urls.currencyL0Url, urls.currencyL1Url, epochProgressOffset)
    };
};

const createDataUpdateTransactionHandler = (urls) => {
    const submitTransaction = async (dataUpdateWithTokenUnlock, proof, dataL1Url) => {
        try {
            const body = { value: dataUpdateWithTokenUnlock, proofs: [proof] };
            return await axios.post(`${dataL1Url}/data`, body);
        } catch (error) {
            console.error('Error sending DataUpdate transaction');
            throw error;
        }
    };

    const sendTransaction = async (sourcePrivateKey, l0Url, l1Url, dataL1Url, tokenLockHash) => {
        const sourceAccount = createAndConnectAccount(sourcePrivateKey, { l0Url, l1Url });

        const dataUpdateWithTokenUnlock = await createUpdateWithTokenUnlockTransaction(sourceAccount, tokenLockHash);
        const proof = await generateProof(dataUpdateWithTokenUnlock, sourcePrivateKey, sourceAccount, SerializerType.STANDARD);

        await submitTransaction(dataUpdateWithTokenUnlock, proof, dataL1Url);
    };

    return {
        sendDataUpdateTransaction: (sourcePrivateKey, tokenLockHash) =>
            sendTransaction(sourcePrivateKey, urls.currencyL0Url, urls.currencyL1Url, urls.dataL1Url, tokenLockHash)
    };
};

const verifyTokenLockExpiration = async (address, hash, initialBalance, urls, unlockEpoch) => {
    const l0Url = urls.currencyL0Url;

    await withRetry(
        async () => {
            const currentEpochProgress = await getEpochProgress(l0Url, true);
            if (currentEpochProgress <= unlockEpoch) {
                throw new Error(
                    `Current epoch progress (${currentEpochProgress}) has not passed unlockEpoch (${unlockEpoch})`
                );
            }
            console.log(`Epoch progress advanced past ${unlockEpoch}`);
        },
        {
            name: `Currency epoch progress advancement`,
            interval: CONSTANTS.EXPIRATION_VERIFICATION_INTERVAL_MS
        }
    );

    const snapshotUrl = `${l0Url}/snapshots/latest/combined`

    const { data: snapshot } = await axios.get(snapshotUrl);

    const activeTokenLocks = snapshot[1]?.activeTokenLocks?.[address]
    if (activeTokenLocks && activeTokenLocks.length > 0) {
        const hasMatchingHash = await findMatchingHash(activeTokenLocks, hash);
        if (hasMatchingHash) {
            throw new Error('Token lock still active after expiration');
        }
    }

    const currentBalance = snapshot[1]?.balances?.[address] || 0;
    const expectedBalance = initialBalance;

    if (currentBalance !== expectedBalance) {
        throw new Error(
            `Balance not reverted correctly after expiration. Expected: ${expectedBalance} (initial), got: ${currentBalance}`
        );
    }

    console.log(`Token lock expired and balance reverted successfully in Currency`);
};

const verifyTriggerTokenUnlock = async (address, initialBalance, urls) => {
    const l0Url = urls.currencyL0Url;

    await withRetry(
        async () => {
            const snapshotUrl = `${l0Url}/snapshots/latest/combined`

            const { data: snapshot } = await axios.get(snapshotUrl);
            const activeTokenLocks = snapshot[1]?.activeTokenLocks?.[address]

            if (activeTokenLocks && Object.keys(activeTokenLocks).length > 0) {
                throw new Error(
                    `TokenLock still active`
                );
            }
            console.log(`TokenLock not active anymore`);
        },
        {
            name: `Check manual token unlock`,
            interval: CONSTANTS.EXPIRATION_VERIFICATION_INTERVAL_MS
        }
    );

    const snapshotUrl = `${l0Url}/snapshots/latest/combined`

    const { data: snapshot } = await axios.get(snapshotUrl);
    const currentBalance = snapshot[1]?.balances?.[address] || 0;
    const expectedBalance = initialBalance;

    if (currentBalance !== expectedBalance) {
        throw new Error(
            `Balance not reverted correctly after triggering token unlcok. Expected: ${expectedBalance} (initial), got: ${currentBalance}`
        );
    }

    console.log(`Token lock expired and balance reverted successfully in Currency`);
};

const executeWorkflow = async ({
   urls,
   workflowName,
   getInitialBalance,
   sendTransaction,
   verifyL1,
   verifyL0,
   verifyGlobalL0,
   verifyBalance
}, privateKey, epochProgressOffset) => {
    const balanceManager = createBalanceManager(urls);
    const transactionHandler = createTokenLockTransactionHandler(urls);
    const verifier = createVerifier(urls);

    console.log(`Starting ${workflowName} workflow`);

    const initialBalance = await getInitialBalance(balanceManager, privateKey);

    const { address, hash, amount, unlockEpoch } = await sendTransaction(transactionHandler, privateKey, epochProgressOffset);

    console.log(`Token lock transaction sent successfully with unlockEpoch: ${unlockEpoch}`);

    await verifyL1(verifier, hash);
    await sleep(CONSTANTS.SNAPSHOT_WAIT_TIME_MS);
    await verifyL0(verifier, address, hash);

    if (verifyGlobalL0) {
        await verifyGlobalL0(verifier, address, hash);
    }

    await verifyBalance(privateKey, balanceManager, initialBalance, amount);

    return {
        address,
        hash,
        initialBalance,
        unlockEpoch
    }
}

const currencyWorkflow = {
    workflowName: 'Currency',
    getInitialBalance: (balanceManager, privateKey) =>
        balanceManager.getCurrencyBalance(privateKey),
    sendTransaction: (handler, privateKey, epochProgressOffset) =>
        handler.sendCurrencyTransaction(privateKey, epochProgressOffset),
    sendDataUpdate: (handler, privateKey) =>
        handler.sendDataUpdateTransaction(privateKey),
    verifyL1: (verifier, hash) =>
        verifier.verifyCurrencyL1(hash),
    verifyL0: (verifier, address, hash) =>
        verifier.verifyCurrencyL0(address, hash),
    verifyGlobalL0: (verifier, address, hash) =>
        verifier.verifyGlobalL0ForCurrency(address, hash),
    verifyBalance: (privateKey, balanceManager, initialBalance, amount) =>
        balanceManager.verifyCurrencyBalanceChange(privateKey, initialBalance, amount)
};

const validateTokenLockExpiration = async (urls) => {
    console.log(`Starting to test TokenLock expiration`)
    const { address, hash, initialBalance, unlockEpoch } = await executeWorkflow({ urls, ...currencyWorkflow }, PRIVATE_KEYS.key1, CONSTANTS.EPOCH_PROGRESS_BUFFER_EXPIRATION_TEST);
    console.log(`Workflow completed successfully, waiting for expiration...`);

    await verifyTokenLockExpiration(
        address,
        hash,
        initialBalance,
        urls,
        unlockEpoch,
    );
    console.log(`TokenLock expiration tested successfully`)
}

const validateTriggerTokenUnlock = async (urls) => {
    console.log(`Starting to test TokenLock triggering unlock`)
    const { address, hash, initialBalance } = await executeWorkflow({ urls, ...currencyWorkflow }, PRIVATE_KEYS.key2, CONSTANTS.EPOCH_PROGRESS_BUFFER_TOKEN_UNLOCK_TEST);

    const dataUpdateTransactionHandler = await createDataUpdateTransactionHandler(urls)
    await dataUpdateTransactionHandler.sendDataUpdateTransaction(PRIVATE_KEYS.key2, hash)

    await verifyTriggerTokenUnlock(
        address,
        initialBalance,
        urls
    )
    console.log(`TokenLock manual token unlock tested successfully`)
}

const executeAllWorkflows = async () => {
    const config = createConfig();
    const urls = createNetworkConfig(config);

    await validateTokenLockExpiration(urls)
    await validateTriggerTokenUnlock(urls)
};

executeAllWorkflows();