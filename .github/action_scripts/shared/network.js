const { dag4 } = require('@stardust-collective/dag4');
const axios = require('axios');

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

const createAndConnectAccount = (privateKey, networkConfig) => {
    const account = dag4.createAccount(privateKey);
    account.connect({
        networkVersion: '2.0',
        ...networkConfig
    });

    return account;
};

const getEpochProgress = async (l0Url, isCurrency = false) => {
    const snapshotUrl = isCurrency
        ? `${l0Url}/data-application/global-snapshot/last-sync`
        : `${l0Url}/global-snapshots/latest`;

    const { data: snapshot } = await axios.get(snapshotUrl);
    return snapshot.value.epochProgress;
};

module.exports = {
    createNetworkConfig,
    createAndConnectAccount,
    getEpochProgress
}