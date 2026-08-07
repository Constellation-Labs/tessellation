const {dag4} = require('@stardust-collective/dag4');
const jsSha256 = require('js-sha256');
const axios = require('axios');
const { serializeBrotli } = require('@stardust-collective/dag4-keystore');
const {parseSharedArgs, withRetry} = require('../shared');
const { PRIVATE_KEYS } = require('../shared/constants');

// Scenario: a UsageUpdateWithFee submitted together with an adequate fee.
// The update is accepted, and the metagraph's combine looks the sibling fee transaction up via
// L0NodeContext.getSnapshotFeeTransactions and records it as `feesPaid` on the device. A non-zero
// feesPaid therefore proves the snapshot-scoped lookup works end to end.

const createConfig = () => {
    const args = process.argv.slice(2);

    if (args.length < 5) {
        throw new Error(
            "Usage: node script.js <dagl0-port-prefix> <dagl1-port-prefix> <ml0-port-prefix> <cl1-port-prefix> <datal1-port-prefix>"
        );
    }

    const sharedArgs = parseSharedArgs(args.slice(0, 5));
    return { ...sharedArgs, privateKey: PRIVATE_KEYS.key1 };
};

const sleep = (ms) => {
    return new Promise(resolve => setTimeout(resolve, ms))
}

const getEncoded = (value) => {
    const energyValue = JSON.stringify(value);
    return energyValue;
};

const serialize = (msg) => {
    const coded = Buffer.from(msg, 'utf8').toString('hex');
    return coded;
};

const generateProof = async (message, walletPrivateKey, account) => {
    const encoded = getEncoded(message);
    const serializedTx = serialize(encoded);
    const hash = jsSha256.sha256(Buffer.from(serializedTx, 'hex'));
    const signature = await dag4.keyStore.sign(walletPrivateKey, hash);

    const publicKey = account.publicKey;
    const uncompressedPublicKey =
        publicKey.length === 128 ? '04' + publicKey : publicKey;

    return {
        id: uncompressedPublicKey.substring(2),
        signature
    };
};

const generateProofFee = async (message, privateKey, account) => {
    const serializedTx = await serializeBrotli(message);
    const messageHash = jsSha256.sha256(Buffer.from(serializedTx));
    const signature = await dag4.keyStore.sign(privateKey, messageHash);

    const publicKey = account.publicKey;
    const uncompressedPublicKey =
        publicKey.length === 128 ? '04' + publicKey : publicKey;

    return {
        id: uncompressedPublicKey.substring(2),
        signature
    };
};

const getFeeWalletBalance = async (globalL0Url, feeWallet) => {
    const targetMetagraphId = process.env.METAGRAPH_ID;
    if (!targetMetagraphId) {
        throw new Error('METAGRAPH_ID is required to verify the target metagraph balance');
    }

    const response = await axios.get(`${globalL0Url}/global-snapshots/latest/combined`);
    const [_, globalSnapshotInfo] = response.data;
    const targetEntry = globalSnapshotInfo.lastCurrencySnapshots?.[targetMetagraphId];

    if (!targetEntry?.Right || targetEntry.Right.length < 2) {
        throw new Error(`Target metagraph ${targetMetagraphId} is not present in the latest global snapshot`);
    }

    return Number(targetEntry.Right[1].balances?.[feeWallet] || 0);
};

const getEstimateFeeResponse = async (metagraphL1DataUrl, update) => {
    const estimateFeeResponse = await withRetry(
        () => axios.post(`${metagraphL1DataUrl}/data/estimate-fee`, update),
        { name: 'POST /data/estimate-fee', maxAttempts: 60, interval: 2000 }
    );
    const {fee, address, updateHash} = estimateFeeResponse.data
    return {
        fee,
        address,
        updateHash
    }
}

const sendDataTransactionsUsingUrls = async (
    globalL0Url,
    metagraphL1DataUrl,
    privateKey
) => {
    const account = dag4.createAccount(privateKey);

    account.connect({
        networkVersion: '2.0',
        l0Url: globalL0Url,
        testnet: true
    });

    const dataUpdate = {
        UsageUpdateWithFee: {
            address: account.address,
            usage: 10
        }
    }
    const dataUpdateProof = await generateProof(dataUpdate, privateKey, account);

    const estimateFeeResponse = await getEstimateFeeResponse(metagraphL1DataUrl, dataUpdate)
    const feeTransaction = {
        amount: estimateFeeResponse.fee,
        dataUpdateRef: estimateFeeResponse.updateHash,
        destination: estimateFeeResponse.address,
        source: account.address
    }
    const feeTransactionProof = await generateProofFee(feeTransaction, privateKey, account);
    const initialFeeWalletBalance = await withRetry(
        () => getFeeWalletBalance(globalL0Url, estimateFeeResponse.address),
        { name: 'read initial fee recipient balance', maxAttempts: 60, interval: 2000 }
    );

    const body = {
        data: {
            value: dataUpdate,
            proofs: [
                dataUpdateProof
            ]
        },
        fee: {
            value: feeTransaction,
            proofs: [
                feeTransactionProof
            ]
        }
    };
    console.log(`Transaction body: ${JSON.stringify(body)}`);
    const response = await withRetry(
        () => axios.post(`${metagraphL1DataUrl}/data`, body),
        { name: 'POST /data', maxAttempts: 60, interval: 2000 }
    );
    console.log(`Response: ${JSON.stringify(response.data)}`);

    return [account.address, estimateFeeResponse, initialFeeWalletBalance];
};

const checkDataTransactionInMetagraphL0 = async (metagraphL0Url, address, expectedFee) => {
    const maxAttempts = 120
    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
            const response = await axios.get(`${metagraphL0Url}/data-application/addresses/${address}`);
            const responseData = response.data;

            if (Object.keys(responseData).length > 0) {
                console.log(`Transaction processed successfully. Response: ${JSON.stringify(responseData)}`);
                // The device state must reflect the fee that combine looked up via
                // getSnapshotFeeTransactions. This is the assertion that proves the feature works.
                // Coerce both sides in case Amount/NonNegLong serialize as {value: N} rather than N.
                const numOf = (x) => (x && typeof x === 'object' && 'value' in x) ? Number(x.value) : Number(x);
                const expected = numOf(expectedFee);
                const actual = numOf(responseData.feesPaid);
                // feesPaid accumulates per combine; assert >= (not ==) so an at-least-once re-combine of
                // the same update (feesPaid = 2*fee) doesn't flaky-fail. A non-zero value proves the fee
                // was looked up via getSnapshotFeeTransactions (a sibling tx, never in the update body).
                if (!(expected > 0) || !Number.isFinite(actual) || actual < expected) {
                    throw new Error(
                        `Fee lookup assertion failed: expected feesPaid>=${expected} (from getSnapshotFeeTransactions) ` +
                        `but device state has feesPaid=${actual}. Full state: ${JSON.stringify(responseData)}`
                    );
                }
                console.log(`Fee lookup verified: device feesPaid=${actual} >= submitted fee ${expected}`);
                return;
            }

            console.log(`Data transaction not processed yet. Retrying in 1 seconds (${attempt}/${maxAttempts})`);
        } catch (error) {
            if (error.message && error.message.startsWith('Fee lookup assertion failed')) {
                throw error;
            }
            console.error(`Attempt ${attempt} failed: ${error.message}`);
        }

        if (attempt === maxAttempts) {
            throw new Error(`Max attempts reached. Could not get state updated after sending data transaction. Please check the logs.`);
        }

        await sleep(1000);
    }
}

const checkFeeTransactionInGlobalL0 = async (globalL0Url, feeWallet, initialBalance) => {
    const maxAttempts = 120
    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
            const currentBalance = await getFeeWalletBalance(globalL0Url, feeWallet);
            if (currentBalance > initialBalance) {
                console.log(
                    `Fee transaction processed successfully. Recipient balance increased from ` +
                    `${initialBalance} to ${currentBalance}`
                );
                return;
            }

            console.log(`Fee transaction not processed yet. Retrying in 1 seconds (${attempt}/${maxAttempts})`);
        } catch (error) {
            console.error(`Attempt ${attempt} failed: ${error.message}`);
        }

        if (attempt === maxAttempts) {
            throw new Error(`Max attempts reached. Could not get state updated after sending data transaction. Please check the logs.`);
        }

        await sleep(1000);
    }
}


const sendDataTransaction = async () => {
    const {dagL0PortPrefix, metagraphL0PortPrefix, dataL1PortPrefix, privateKey} = createConfig()

    const host = process.env.TEST_HOST || 'http://localhost';
    const globalL0Url = process.env.GL0_URL || `${host}:${dagL0PortPrefix}00`;
    const metagraphL0Url = process.env.ML0_URL || `${host}:${metagraphL0PortPrefix}00`;
    const metagraphL1DataUrl = process.env.DL1_URL || `${host}:${dataL1PortPrefix}00`;

    const [address, estimateFeeResponse, initialFeeWalletBalance] =
        await sendDataTransactionsUsingUrls(globalL0Url, metagraphL1DataUrl, privateKey);

    await checkDataTransactionInMetagraphL0(metagraphL0Url, address, estimateFeeResponse.fee);
    await checkFeeTransactionInGlobalL0(globalL0Url, estimateFeeResponse.address, initialFeeWalletBalance);
};

sendDataTransaction();
