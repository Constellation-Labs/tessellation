const { clusterCheck, checkGlobalL0Node, checkCurrencyL0Node } = require("./shared")
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

const checkCurrencyL1Node = async (config) => {
    const { currencyL1PortPrefix } = config
    const infos = [
        {
            name: 'Currency L1 - 1',
            baseUrl: `http://localhost:${currencyL1PortPrefix}00`
        },
        {
            name: 'Currency L1 - 2',
            baseUrl: `http://localhost:${currencyL1PortPrefix}10`
        },
        {
            name: 'Currency L1 - 3',
            baseUrl: `http://localhost:${currencyL1PortPrefix}20`
        }
    ];
    await clusterCheck( infos, false, 'Currency L1', 3, false );
};

const main = async () => {
    const config = createConfig()

    await checkGlobalL0Node(config);
    await checkCurrencyL0Node(config);
    await checkCurrencyL1Node(config);
};

main();