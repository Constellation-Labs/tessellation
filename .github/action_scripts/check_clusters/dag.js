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