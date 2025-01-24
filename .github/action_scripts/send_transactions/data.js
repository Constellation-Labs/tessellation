const { dag4 } = require('@stardust-collective/dag4');
const jsSha256 = require('js-sha256');
const axios = require('axios');
const { parseSharedArgs } = require('../shared');

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

const sendDataTransactionsUsingUrls = async (
    globalL0Url,
    metagraphL1DataUrl
) => {
    const exampleWalletPrivateKey = 'e948da6dce90ebb10d73d829330e6a9d940fc5b95a71f27e874934f904c840bb'
    const account = dag4.createAccount(exampleWalletPrivateKey);

    account.connect({
        networkVersion: '2.0',
        l0Url: globalL0Url,
        testnet: true
    });

    const message = {
        address: account.address,
        usage: 10
    }
    const proof = await generateProof(message, exampleWalletPrivateKey, account);
    const body = {
        value: {
            ...message
        },
        proofs: [
            proof
        ]
    };
    try {
        console.log(`Transaction body: ${JSON.stringify(body)}`);
        const response = await axios.post(`${metagraphL1DataUrl}/data`, body);
        console.log(`Response: ${JSON.stringify(response.data)}`);
    } catch (e) {
        console.log('Error sending transaction', e.message);
    }
    return account.address;
};

const sendDataTransaction = async () => {
    const {dagL0PortPrefix, metagraphL0PortPrefix, dataL1PortPrefix} = createConfig()

    const globalL0Url = `http://localhost:${dagL0PortPrefix}00`;
    const metagraphL0Url = `http://localhost:${metagraphL0PortPrefix}00`;
    const metagraphL1DataUrl = `http://localhost:${dataL1PortPrefix}00`;

    const address = await sendDataTransactionsUsingUrls(globalL0Url, metagraphL1DataUrl);

    for (let idx = 0; idx < 20; idx++) {
        try {
            const response = await axios.get(`${metagraphL0Url}/data-application/addresses/${address}`)
            const responseData = response.data
            if (Object.keys(responseData).length > 0) {
                console.log(`Transaction processed successfully, response: ${JSON.stringify(responseData)}`)
                return
            }
            if (idx === 19) {
                throw Error(`Could not get state updated after sending data transaction, please check the logs`)
            }

            console.log(`Data transaction not processed yet, trying again in 10s (${idx + 1} / 20)`)
            await sleep(10 * 1000)
        } catch (e) {
            if (idx === 19) {
                throw Error(`Could not get state updated after sending data transaction, please check the logs`)
            }
            console.log(`Data transaction not processed yet, trying again in 10s (${idx + 1} / 20)`)
            await sleep(10 * 1000)
        }
    }
};

sendDataTransaction();