const { clusterCheck } = require("./shared")

const checkGlobalL0Node = async () => {
  const infos = [
    {
      name: 'Global L0',
      baseUrl: 'http://localhost:9000'
    }
  ];
  await clusterCheck(infos, true, 'Global L0', 1, true);
};

const checkCurrencyL0Node = async () => {
    const infos = [
        {
            name: 'Currency L0 - 1',
            baseUrl: 'http://localhost:9400'
        },
        {
            name: 'Currency L0 - 2',
            baseUrl: 'http://localhost:9500'
        },
        {
            name: 'Currency L0 - 3',
            baseUrl: 'http://localhost:9600'
        }
    ];
    await clusterCheck( infos, true, 'Currency L0', 3, false );
};

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