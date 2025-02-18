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

const SerializerType = {
    STANDARD: 'standard',
    BROTLI: 'brotli'
};

const brotliSerialize = async (content, compressionLevel = CONSTANTS.DEFAULT_COMPRESSION_LEVEL) => {
    const jsonString = content;
    const encoder = new TextEncoder();
    const utf8Bytes = encoder.encode(jsonString);
    return compress(utf8Bytes, { quality: compressionLevel });
};

const createSerializer = (type = SerializerType.STANDARD) => {
    const standardSerializer = {
        serialize: (value) =>
            Buffer.from(sortedJsonStringify(value), 'utf8').toString('hex')
    };

    const brotliSerializer = {
        serialize: async (value) =>
            await brotliSerialize(sortedJsonStringify(value))
    };

    return type === SerializerType.BROTLI ? brotliSerializer : standardSerializer;
};

const generateProof = async (message, walletPrivateKey, account, serializerType = SerializerType.STANDARD) => {
    const serializer = createSerializer(serializerType);
    const serializedTx = await serializer.serialize(message);
    const hash = jsSha256.sha256(Buffer.from(serializedTx, 'hex'));

    const signature = await dag4.keyStore.sign(walletPrivateKey, hash);
    const publicKey = account.publicKey;
    const cleanPublicKey = publicKey.length === 130 ? publicKey.substring(2) : publicKey;

    return { id: cleanPublicKey, signature };
};

module.exports = {
    sortedJsonStringify,
    SerializerType,
    createSerializer,
    generateProof,
};