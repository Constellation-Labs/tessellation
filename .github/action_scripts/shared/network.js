const { dag4 } = require('@stardust-collective/dag4');
const axios = require('axios');
const { withRetry } = require("./operations");

const createNetworkConfig = (args) => {
    const { dagL0PortPrefix, dagL1PortPrefix, metagraphL0PortPrefix, currencyL1PortPrefix, dataL1PortPrefix } = args;
    const host = process.env.TEST_HOST || 'http://localhost';

    return {
        globalL0Url: process.env.GL0_URL || `${host}:${dagL0PortPrefix}00`,
        dagL1Url: process.env.GL1_URL || `${host}:${dagL1PortPrefix}00`,
        currencyL0Url: process.env.ML0_URL || `${host}:${metagraphL0PortPrefix}00`,
        currencyL1Url: process.env.CL1_URL || `${host}:${currencyL1PortPrefix}00`,
        dataL1Url: process.env.DL1_URL || `${host}:${dataL1PortPrefix}00`,
        extendedDagL1Url: process.env.EXT_GL1_URL || `${host}:${dagL1PortPrefix}50`,
        extendedDataL1Url: process.env.EXT_DL1_URL || `${host}:${dataL1PortPrefix}50`
    };
};

const createAndConnectAccount = (privateKey, networkConfig) => {
    const account = dag4.createAccount(privateKey);
    account.connect({
        networkVersion: '2.0',
        ...networkConfig
    });

    return account;
};

const checkIfEpochProgressCanBeFetched = async (snapshotUrl, isCurrency) => {
    await withRetry(
        async () => {
            const { data: snapshot } = await axios.get(snapshotUrl);
            const epochProgress = isCurrency ?
                snapshot.value.globalSyncView.epochProgress :
                snapshot.value.epochProgress;

            if (!epochProgress) {
                throw new Error("EpochProgress still in sync process")
            }
        },
        { name: `Get epoch progress` }
    );
}

const getEpochProgress = async (l0Url, isCurrency = false) => {
    const snapshotUrl = isCurrency
        ? `${l0Url}/snapshots/latest`
        : `${l0Url}/global-snapshots/latest`;

    await checkIfEpochProgressCanBeFetched(snapshotUrl, isCurrency)

    const { data: snapshot } = await axios.get(snapshotUrl);
    return isCurrency ?
        snapshot.value.globalSyncView.epochProgress :
        snapshot.value.epochProgress;
}

module.exports = {
    createNetworkConfig,
    createAndConnectAccount,
    getEpochProgress
}