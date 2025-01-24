const { clusterCheck, checkGlobalL0Node } = require("./shared")
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

const checkDAGL1Node = async (config) => {
  const { dagL1PortPrefix } = config
  const infos = [
    {
      name: 'DAG L1 - 1',
      baseUrl: `http://localhost:${dagL1PortPrefix}00`
    },
    {
      name: 'DAG L1 - 2',
      baseUrl: `http://localhost:${dagL1PortPrefix}10`
    },
    {
      name: 'DAG L1 - 3',
      baseUrl: `http://localhost:${dagL1PortPrefix}20`
    }
  ];
  await clusterCheck(infos, false, 'DAG L1', 3, true);
};

const main = async () => {
  const config = createConfig()

  await checkGlobalL0Node(config);
  await checkDAGL1Node(config);
};

main();