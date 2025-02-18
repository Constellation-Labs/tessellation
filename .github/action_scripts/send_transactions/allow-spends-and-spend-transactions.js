const { dag4 } = require('@stardust-collective/dag4');
const axios = require('axios');
const jsSha256 = require('js-sha256');
const {
    parseSharedArgs,
    CONSTANTS: sharedConstants,
    PRIVATE_KEYS,
    generateProofWithBrotli,
    sleep,
    withRetry,
    createAndConnectAccount,
    createNetworkConfig,
    getEpochProgress,
    brotliSerialize
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

const findMatchingHash = async (allowSpends, targetHash) => {
    return allowSpends.reduce(async (acc, allowSpend) => {
        const prevResult = await acc;
        if (prevResult) return true;

        const message = await brotliSerialize(allowSpend.value);
        const allowSpendHash = jsSha256.sha256(Buffer.from(message, 'hex'));
        return allowSpendHash === targetHash;
    }, Promise.resolve(false));
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
    const verifyInL1 = async (hash, l1Url, layerName) => {
        await withRetry(
            async () => {
                const response = await axios.get(`${l1Url}/allow-spends/${hash}`);
                if (!response.data) {
                    throw new Error('Transaction not found');
                }
                console.log(`AllowSpend transaction processed successfully in ${layerName} L1`);
            },
            { name: `${layerName} L1 verification` }
        );
    };

    const verifyAllowSpendInSnapshot = async (address, hash, snapshot, tokenId, layerName, isCurrency = false) => {
        const activeAllowSpendsForAddress = isCurrency
            ? snapshot[1]?.activeAllowSpends?.[address]
            : snapshot[1]?.activeAllowSpends?.[tokenId]?.[address];

        if (!activeAllowSpendsForAddress) {
            throw new Error(`No active allow spends found for address ${address} in ${layerName}`);
        }

        const hasMatchingHash = await findMatchingHash(activeAllowSpendsForAddress, hash);
        if (!hasMatchingHash) {
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
            console.log(`AllowSpendBody: ${JSON.stringify(body)}`)
            console.log(`Url: ${l1Url}/allow-spends`)
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
        const proof = await generateProofWithBrotli(allowSpend, sourcePrivateKey, sourceAccount);

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
    verifyBalance
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

const executeAllWorkflows = async () => {
    const config = createConfig();
    const urls = createNetworkConfig(config);

    await executeWorkflow({ urls, ...dagWorkflow });
    await executeWorkflow({ urls, ...currencyWorkflow });
};

executeAllWorkflows();