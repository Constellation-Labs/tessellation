const { clusterCheck, checkGlobalL0Node, checkCurrencyL0Node } = require("./shared")

const checkDataL1Node = async () => {
    const infos = [
        {
            name: 'Data L1 - 1',
            baseUrl: 'http://localhost:7000'
        },
        {
            name: 'Data L1 - 2',
            baseUrl: 'http://localhost:7100'
        },
        {
            name: 'Data L1 - 3',
            baseUrl: 'http://localhost:7200'
        }
    ];
    await clusterCheck( infos, false, 'Data L1', 3, false );
};

const main = async () => {
    await checkGlobalL0Node();
    await checkCurrencyL0Node();
    await checkDataL1Node();
};

main();