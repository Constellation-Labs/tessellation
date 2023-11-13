const { clusterCheck, checkGlobalL0Node } = require("./shared")

const checkDAGL1Node = async () => {
  const infos = [
    {
      name: 'DAG L1 - 1',
      baseUrl: 'http://localhost:9100'
    },
    {
      name: 'DAG L1 - 2',
      baseUrl: 'http://localhost:9200'
    },
    {
      name: 'DAG L1 - 3',
      baseUrl: 'http://localhost:9300'
    }
  ];
  await clusterCheck(infos, false, 'DAG L1', 3, true);
};

const main = async () => {
  await checkGlobalL0Node();
  await checkDAGL1Node();
};

main();