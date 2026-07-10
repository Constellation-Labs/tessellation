const { clusterCheck, checkGlobalL0Node } = require("./shared")
const { parseSharedArgs } = require('../shared');

const createConfig = () => {
  const args = process.argv.slice(2);

  if (args.length < 5) {
    throw new Error(
        "Usage: node script.js <dagl0-port-prefix> <dagl1-port-prefix> <ml0-port-prefix> <cl1-port-prefix> <datal1-port-prefix> <extended-dag-l1>"
    );
  }

  const sharedArgs = parseSharedArgs(args.slice(0, 5));
  const extendedDAGL1 = args[5] === 'true';
  return { ...sharedArgs, extendedDAGL1 };
};

const checkDAGL1Node = async (config) => {
  const { dagL1PortPrefix } = config
  const host = process.env.TEST_HOST || 'http://localhost';
  const isRemote = host && host !== 'http://localhost';
  const gl1Url = process.env.GL1_URL || `${host}:${dagL1PortPrefix}00`;

  if (isRemote) {
    const infos = [{ name: 'DAG L1', baseUrl: gl1Url }];
    await clusterCheck(infos, false, 'DAG L1', 1, true);
  } else {
    const baseNodes = [
      { name: 'DAG L1 - 1', baseUrl: `${host}:${dagL1PortPrefix}00` },
      { name: 'DAG L1 - 2', baseUrl: `${host}:${dagL1PortPrefix}10` },
      { name: 'DAG L1 - 3', baseUrl: `${host}:${dagL1PortPrefix}20` },
    ];
    const extendedNodes = [
      { name: 'DAG L1 - 4', baseUrl: `${host}:${dagL1PortPrefix}30` },
      { name: 'DAG L1 - 5', baseUrl: `${host}:${dagL1PortPrefix}40` },
      { name: 'DAG L1 - 6', baseUrl: `${host}:${dagL1PortPrefix}50` },
    ];
    const infos = config.extendedDAGL1 ? [...baseNodes, ...extendedNodes] : baseNodes;
    await clusterCheck(infos, false, 'DAG L1', config.extendedDAGL1 ? 6 : 3, true);
  }
};

const main = async () => {
  const config = createConfig()

  await checkGlobalL0Node(config);
  await checkDAGL1Node(config);
};

main();