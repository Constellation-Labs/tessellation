const { clusterCheck, checkGlobalL0Node, checkCurrencyL0Node } = require("./shared")

const checkCurrencyL1Node = async () => {
    const infos = [
        {
            name: 'Currency L1 - 1',
            baseUrl: 'http://localhost:9700'
        },
        {
            name: 'Currency L1 - 2',
            baseUrl: 'http://localhost:9800'
        },
        {
            name: 'Currency L1 - 3',
            baseUrl: 'http://localhost:9900'
        }
    ];
    await clusterCheck( infos, false, 'Currency L1', 3, false );
};

const main = async () => {
    await checkGlobalL0Node();
    await checkCurrencyL0Node();
    await checkCurrencyL1Node();
};

main();