const {dag4} = require('@stardust-collective/dag4');
const jsSha256 = require('js-sha256');
const axios = require('axios');
const { z } = require('zod');
const { parseSharedArgs } = require('../shared');

const CliArgsSchema = z.object({
    privateKey: z.string()
        .min(1, "Private key cannot be empty"),
});

const createConfig = () => {
    const args = process.argv.slice(2);

    if (args.length < 6) {
        throw new Error(
            "Usage: node script.js <dagl0-port-prefix> <dagl1-port-prefix> <ml0-port-prefix> <cl1-port-prefix> <datal1-port-prefix> <private-key>"
        );
    }

    const sharedArgs = parseSharedArgs(args.slice(0, 5));
    const [privateKey] = args.slice(5);

    const specificArgs = CliArgsSchema.parse({ privateKey });

    return { ...sharedArgs, ...specificArgs };
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

const getEstimateFeeResponse = async (metagraphL1DataUrl, update) => {
    try {
        const estimateFeeResponse = await axios.post(`${metagraphL1DataUrl}/data/estimate-fee`, update)
        const {fee, address, updateHash} = estimateFeeResponse.data
        return {
            fee,
            address,
            updateHash
        }
    } catch (e) {
        console.error(`Could not get estimate fee response`, e)
        throw e
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
        source: account.address,
        destination:estimateFeeResponse.address,
        amount: estimateFeeResponse.fee,
        dataUpdateRef: estimateFeeResponse.updateHash
    }
    const feeTransactionProof = await generateProof(feeTransaction, privateKey, account);

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
    try {
        console.log(`Transaction body: ${JSON.stringify(body)}`);
        const response = await axios.post(`${metagraphL1DataUrl}/data`, body);
        console.log(`Response: ${JSON.stringify(response.data)}`);
    } catch (e) {
        console.log('Error sending transaction', e);
    }

    return [account.address, estimateFeeResponse];
};

const checkDataTransactionInMetagraphL0 = async (metagraphL0Url, address) => {
    const maxAttempts = 60
    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
            const response = await axios.get(`${metagraphL0Url}/data-application/addresses/${address}`);
            const responseData = response.data;

            if (Object.keys(responseData).length > 0) {
                console.log(`Transaction processed successfully. Response: ${JSON.stringify(responseData)}`);
                return;
            }

            console.log(`Data transaction not processed yet. Retrying in 1 seconds (${attempt}/${maxAttempts})`);
        } catch (error) {
            console.error(`Attempt ${attempt} failed: ${error.message}`);
        }

        if (attempt === maxAttempts) {
            throw new Error(`Max attempts reached. Could not get state updated after sending data transaction. Please check the logs.`);
        }

        await sleep(1000);
    }
}

const checkFeeTransactionInGlobalL0 = async (globalL0Url, feeWallet) => {
    const maxAttempts = 60
    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
            const response = await axios.get(`${globalL0Url}/global-snapshots/latest/combined`);
            const [_, globalSnapshotInfo] = response.data;

            const firstSnapshotKey = Object.keys(globalSnapshotInfo.lastCurrencySnapshots)[0];
            const metagraphSnapshotBalances = globalSnapshotInfo.lastCurrencySnapshots[firstSnapshotKey].Right[1].balances;
            if (Object.keys(metagraphSnapshotBalances).length > 0 && metagraphSnapshotBalances[feeWallet] > 0) {
                console.log(`Fee transaction processed successfully. Response: ${JSON.stringify(metagraphSnapshotBalances)}`);
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

    const globalL0Url = `http://localhost:${dagL0PortPrefix}00`;
    const metagraphL0Url = `http://localhost:${metagraphL0PortPrefix}00`;
    const metagraphL1DataUrl = `http://localhost:${dataL1PortPrefix}00`;

    const [address, estimateFeeResponse] = await sendDataTransactionsUsingUrls(globalL0Url, metagraphL1DataUrl, privateKey);

    await checkDataTransactionInMetagraphL0(metagraphL0Url, address);
    await checkFeeTransactionInGlobalL0(globalL0Url, estimateFeeResponse.address);
};

sendDataTransaction();