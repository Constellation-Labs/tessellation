const { dag4 } = require('@stardust-collective/dag4');
const { compress } = require('brotli');
const jsSha256 = require('js-sha256');
const { CONSTANTS } = require("./constants");

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

const generateProofWithSerializer = async (message, walletPrivateKey, account, serializer) => {
    const serializedTx = await serializer(message);
    const hash = jsSha256.sha256(Buffer.from(serializedTx, "hex"));
    const signature = await dag4.keyStore.sign(walletPrivateKey, hash);

    const publicKey = account.publicKey;
    const cleanPublicKey = publicKey.length === 130 ? publicKey.substring(2) : publicKey;

    return { id: cleanPublicKey, signature };
};

const generateProof = async (message, walletPrivateKey, account) => {
    return generateProofWithSerializer(
        message,
        walletPrivateKey,
        account,
        (msg) => Buffer.from(JSON.stringify(msg), 'utf8').toString('hex')
    );
};

const generateProofWithBrotli = async (message, walletPrivateKey, account) => {
    return generateProofWithSerializer(message, walletPrivateKey, account, brotliSerialize);
};

module.exports = {
    generateProof,
    generateProofWithBrotli,
    brotliSerialize
};