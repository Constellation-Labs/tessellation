const { dag4 } = require('@stardust-collective/dag4');
const jsSha256 = require('js-sha256');
const axios = require('axios');
const { compress } = require('brotli');
const { parseSharedArgs } = require('../shared');

const CONSTANTS = {
  MAX_VERIFICATION_ATTEMPTS: 60,
  VERIFICATION_INTERVAL_MS: 1000,
  EXPIRATION_VERIFICATION_INTERVAL_MS: 10 * 1000,
  SNAPSHOT_WAIT_TIME_MS: 5 * 1000,
  DEFAULT_COMPRESSION_LEVEL: 2,
  DEFAULT_LAST_VALID_EPOCH_PROGRESS: 50,
  CURRENCY_TOKEN_ID: process.env.METAGRAPH_ID,
  EPOCH_PROGRESS_BUFFER: 5,
  TRANSACTION_FEE: 1
};

const PRIVATE_KEYS = {
  key1: '595a30ab6c62ae48a23414951e2703f49f8c0040b9801738ad3550475389d811',
  key2: 'e70e7972630a49f90b0bfb55557287634dbdeb1a6147bba90ac8e3a65e0b41e8',
  key3: '4af811856157548d1f24316ffd9cb9b87fb9e2327d3ce702862cb6e5b39dd219',
  key4: 'dfc636ffb5abd844f6d5600f753923df5d8f63de2851d1eb6dd8ce3ac61e9699',
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

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

const sortAndRemoveNulls = (obj) => {
    const processValue = (value) => {
        if (value === null) return undefined;
        if (Array.isArray(value)) {
            return value.map(v => processValue(v)).filter(v => v !== undefined);
        }
        if (typeof value === 'object') {
            return sortAndRemoveNulls(value);
        }
        return value;
    };

    const cleanObj = Object.entries(obj)
        .reduce((acc, [key, value]) => {
            const processed = processValue(value);
            if (processed !== undefined) {
                acc[key] = processed;
            }
            return acc;
        }, {});

    return Object.keys(cleanObj)
        .sort()
        .reduce((acc, key) => {
            acc[key] = cleanObj[key];
            return acc;
        }, {});
};

const sortedJsonStringify = (obj) => JSON.stringify(sortAndRemoveNulls(obj));

const brotliSerialize = async (content, compressionLevel = CONSTANTS.DEFAULT_COMPRESSION_LEVEL) => {
    const jsonString = sortedJsonStringify(content);
    const encoder = new TextEncoder();
    const utf8Bytes = encoder.encode(jsonString);
    return compress(utf8Bytes, { quality: compressionLevel });
};

const createNetworkConfig = (args) => {
    const { dagL0PortPrefix, dagL1PortPrefix, metagraphL0PortPrefix, currencyL1PortPrefix, dataL1PortPrefix } = args;
    
    return {
        globalL0Url: `http://localhost:${dagL0PortPrefix}00`,
        dagL1Url: `http://localhost:${dagL1PortPrefix}00`,
        currencyL0Url: `http://localhost:${metagraphL0PortPrefix}00`,
        currencyL1Url: `http://localhost:${currencyL1PortPrefix}00`,
        dataL1Url: `http://localhost:${dataL1PortPrefix}00`
    };
};

const createAndConnectAccount = (privateKey, networkConfig, isCurrency = false) => {
    const account = dag4.createAccount(privateKey);
    account.connect({
        networkVersion: '2.0',
        ...networkConfig
    });
    
    return account;
};

const generateProof = async (message, walletPrivateKey, account) => {
    const serializedTx = await brotliSerialize(message);
    const hash = jsSha256.sha256(Buffer.from(serializedTx, "hex"));
    const signature = await dag4.keyStore.sign(walletPrivateKey, hash);
    
    const publicKey = account.publicKey;
    const cleanPublicKey = publicKey.length === 130 ? publicKey.substring(2) : publicKey;

    return { id: cleanPublicKey, signature };
};

const getEpochProgress = async (l0Url, isCurrency = false) => {
    const snapshotUrl = isCurrency 
        ? `${l0Url}/snapshots/latest/combined`
        : `${l0Url}/global-snapshots/latest/combined`;
    
    const { data: snapshot } = await axios.get(snapshotUrl);
    return snapshot[0].value.epochProgress;
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
            await operation();
            return;
        } catch (error) {
            handleError(error, attempt);
            
            if (attempt === maxAttempts) {
                throw new Error(`Max attempts reached for ${name}`);
            }
            
            await sleep(interval);
        }
    }
};

const createVerifier = (urls) => {
    const findMatchingHash = async (allowSpends, targetHash) => {
        return allowSpends.reduce(async (acc, allowSpend) => {
            const prevResult = await acc;
            if (prevResult) return true;
            
            const message = await brotliSerialize(allowSpend.value);
            const allowSpendHash = jsSha256.sha256(Buffer.from(message, 'hex'));
            return allowSpendHash === targetHash;
        }, Promise.resolve(false));
    };

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
        const proof = await generateProof(allowSpend, sourcePrivateKey, sourceAccount);

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