const {dag4} = require('@stardust-collective/dag4');
const jsSha256 = require('js-sha256');
const axios = require('axios');
const { compress } = require("brotli");
const {parseSharedArgs} = require('../shared');
const { PRIVATE_KEYS } = require('../shared/constants');

// Negative fee scenarios for UsageUpdateWithFee (which the template requires a fee of >= 100 for):
//   insufficient -> a fee transaction below minFee (50) -> NotEnoughFee
//   missing      -> no fee transaction at all           -> MissingFeeTransaction
// Both must be REJECTED, so the update never reaches metagraph-L0 calculated state. We confirm the
// cluster is live (metagraph L0 keeps producing snapshots) and then assert the address has no state.

const SCENARIOS = {
    insufficient: { key: PRIVATE_KEYS.key2, feeAmount: 50 },
    missing:      { key: PRIVATE_KEYS.key3, feeAmount: null }
};

const createConfig = () => {
    const args = process.argv.slice(2);
    if (args.length < 6) {
        throw new Error(
            "Usage: node script.js <dagl0-port-prefix> <dagl1-port-prefix> <ml0-port-prefix> <cl1-port-prefix> <datal1-port-prefix> <scenario:insufficient|missing>"
        );
    }
    const sharedArgs = parseSharedArgs(args.slice(0, 5));
    const scenario = args[5];
    if (!SCENARIOS[scenario]) {
        throw new Error(`Unknown scenario "${scenario}". Expected one of: ${Object.keys(SCENARIOS).join(', ')}`);
    }
    return { ...sharedArgs, scenario };
};

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

const serialize = (msg) => Buffer.from(msg, 'utf8').toString('hex');
const serializeBrotli = (content, level = 2) =>
    compress(new TextEncoder().encode(JSON.stringify(content)), {quality: level});

const generateProof = async (message, privateKey, account) => {
    const hash = jsSha256.sha256(Buffer.from(serialize(JSON.stringify(message)), 'hex'));
    const signature = await dag4.keyStore.sign(privateKey, hash);
    const publicKey = account.publicKey;
    const uncompressed = publicKey.length === 128 ? '04' + publicKey : publicKey;
    return {id: uncompressed.substring(2), signature};
};

const generateProofFee = async (message, privateKey, account) => {
    const hash = jsSha256.sha256(Buffer.from(await serializeBrotli(message), 'hex'));
    const signature = await dag4.keyStore.sign(privateKey, hash);
    const publicKey = account.publicKey;
    const uncompressed = publicKey.length === 128 ? '04' + publicKey : publicKey;
    return {id: uncompressed.substring(2), signature};
};

const getMl0LatestOrdinal = async (metagraphL0Url) => {
    const r = await axios.get(`${metagraphL0Url}/snapshots/latest`);
    const d = r.data || {};
    const raw = (d.value && d.value.ordinal != null) ? d.value.ordinal : d.ordinal;
    return (raw && typeof raw === 'object') ? raw.value : raw;
};

const submitInvalidUpdate = async (globalL0Url, metagraphL1DataUrl, scenario) => {
    const {key, feeAmount} = SCENARIOS[scenario];
    const account = dag4.createAccount(key);
    account.connect({networkVersion: '2.0', l0Url: globalL0Url, testnet: true});

    const dataUpdate = {UsageUpdateWithFee: {address: account.address, usage: 10}};
    const dataUpdateProof = await generateProof(dataUpdate, key, account);

    let body;
    if (scenario === 'missing') {
        // UsageUpdateWithFee submitted with NO fee transaction -> MissingFeeTransaction.
        body = {value: dataUpdate, proofs: [dataUpdateProof]};
    } else {
        // Fetch the update hash so the fee references the update, but pay below minFee -> NotEnoughFee.
        const est = (await axios.post(`${metagraphL1DataUrl}/data/estimate-fee`, dataUpdate)).data;
        const feeTransaction = {
            amount: feeAmount,
            dataUpdateRef: est.updateHash,
            destination: est.address,
            source: account.address
        };
        const feeProof = await generateProofFee(feeTransaction, key, account);
        body = {
            data: {value: dataUpdate, proofs: [dataUpdateProof]},
            fee: {value: feeTransaction, proofs: [feeProof]}
        };
    }

    try {
        console.log(`[${scenario}] Submitting: ${JSON.stringify(body)}`);
        const resp = await axios.post(`${metagraphL1DataUrl}/data`, body);
        console.log(`[${scenario}] POST /data response: ${JSON.stringify(resp.data)}`);
    } catch (e) {
        // A synchronous rejection at submission is an acceptable (and expected) outcome.
        console.log(`[${scenario}] POST /data rejected at submission: ${e.response ? JSON.stringify(e.response.data) : e.message}`);
    }
    return account.address;
};

const assertUpdateRejected = async (metagraphL0Url, address, scenario) => {
    // Liveness anchor: wait for the metagraph L0 to produce several snapshots after submission so a
    // VALID update would have been included by now. Only then is "no device state" a real rejection.
    const snapshotsToWait = 3;
    const maxAttempts = 240;
    let startOrd = null;
    try { startOrd = await getMl0LatestOrdinal(metagraphL0Url); } catch (e) { /* fall back to time */ }

    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
        let advancedEnough = false;
        if (startOrd != null) {
            try {
                const cur = await getMl0LatestOrdinal(metagraphL0Url);
                advancedEnough = cur != null && cur >= startOrd + snapshotsToWait;
            } catch (e) { /* ignore transient */ }
        } else {
            advancedEnough = attempt >= 90; // ~90s fallback if ordinal is unreadable
        }

        if (advancedEnough) {
            let resp;
            try {
                resp = await axios.get(`${metagraphL0Url}/data-application/addresses/${address}`);
            } catch (e) {
                if (e.response && e.response.status === 404) {
                    console.log(`[${scenario}] PASS: update correctly rejected (address ${address} has no device state).`);
                    return;
                }
                throw e;
            }
            throw new Error(
                `[${scenario}] FAIL: rejected update was ACCEPTED. Device state present for ${address}: ${JSON.stringify(resp.data)}`
            );
        }

        console.log(`[${scenario}] Waiting for metagraph L0 to advance ${snapshotsToWait} snapshots before asserting rejection (${attempt}/${maxAttempts})`);
        await sleep(1000);
    }
    throw new Error(`[${scenario}] Timed out waiting for metagraph L0 to advance; cannot confirm rejection.`);
};

const run = async () => {
    const {dagL0PortPrefix, metagraphL0PortPrefix, dataL1PortPrefix, scenario} = createConfig();
    const host = process.env.TEST_HOST || 'http://localhost';
    const globalL0Url = process.env.GL0_URL || `${host}:${dagL0PortPrefix}00`;
    const metagraphL0Url = process.env.ML0_URL || `${host}:${metagraphL0PortPrefix}00`;
    const metagraphL1DataUrl = process.env.DL1_URL || `${host}:${dataL1PortPrefix}00`;

    const address = await submitInvalidUpdate(globalL0Url, metagraphL1DataUrl, scenario);
    await assertUpdateRejected(metagraphL0Url, address, scenario);
};

run().catch((e) => { console.error(e.message || e); process.exit(1); });
