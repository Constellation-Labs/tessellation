const { clusterCheck, checkGlobalL0Node, checkCurrencyL0Node } = require("./shared")
const { parseSharedArgs } = require('../shared');

const createConfig = () => {
    const args = process.argv.slice(2);

    if (args.length < 5) {
        throw new Error(
            "Usage: node script.js <dagl0-port-prefix> <dagl1-port-prefix> <ml0-port-prefix> <cl1-port-prefix> <datal1-port-prefix> <extended-datal1>"
        );
    }

    const sharedArgs = parseSharedArgs(args.slice(0, 5));
    const extendedDataL1 = args[5] === 'true';
    return { ...sharedArgs, extendedDataL1 };
};

const checkDataL1Node = async (config) => {
    const { dataL1PortPrefix } = config
    const host = process.env.TEST_HOST || 'http://localhost';
    const isRemote = host && host !== 'http://localhost';
    const dl1Url = process.env.DL1_URL || `${host}:${dataL1PortPrefix}00`;

    if (isRemote) {
        const infos = [{ name: 'Data L1', baseUrl: dl1Url }];
        await clusterCheck( infos, false, 'Data L1', 1, false );
    } else {
        const baseNodes = [
            { name: 'Data L1 - 1', baseUrl: `${host}:${dataL1PortPrefix}00` },
            { name: 'Data L1 - 2', baseUrl: `${host}:${dataL1PortPrefix}10` },
            { name: 'Data L1 - 3', baseUrl: `${host}:${dataL1PortPrefix}20` },
        ];
        const extendedNodes = [
            { name: 'Data L1 - 4', baseUrl: `${host}:${dataL1PortPrefix}30` },
            { name: 'Data L1 - 5', baseUrl: `${host}:${dataL1PortPrefix}40` },
            { name: 'Data L1 - 6', baseUrl: `${host}:${dataL1PortPrefix}50` },
        ];
        const infos = config.extendedDataL1 ? [...baseNodes, ...extendedNodes] : baseNodes;
        await clusterCheck( infos, false, 'Data L1', config.extendedDataL1 ? 6 : 3, false );
    }
};

const main = async () => {
    const config = createConfig()

    await checkGlobalL0Node(config);
    await checkCurrencyL0Node(config);
    await checkDataL1Node(config);
};

main();