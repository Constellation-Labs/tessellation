const axios = require('axios');

const fetchData = async (url, maxRetries = 12, retryIntervalMs = 5000) => {
  let lastError = null;
  for (let idx = 0; idx < maxRetries; idx++) {
    try {
      const response = await axios.get(url, {
        headers: {
          Accept: 'application/json'
        },
        timeout: 10000
      });

      const { data } = response
      console.log(`URL ${url} response: ${JSON.stringify(data)}`)

      return data
    } catch (e) {
      lastError = e;
      console.log(`fetchData attempt ${idx + 1}/${maxRetries} failed for ${url}: ${e.message}`);
      if (idx < maxRetries - 1) {
        await sleep(retryIntervalMs);
      }
    }
  }
  throw Error(`Error fetching data from ${url} after ${maxRetries} attempts: ${lastError?.message}`);
};

const sleep = (ms) => {
  return new Promise((resolve) => setTimeout(resolve, ms));
};

const checkIfNodeIsReady = async (url, name) => {
  console.log(`Checking if ${name} is ready`);
  const checkInterval = 10 * 1000;
  const maxAttempts = 24; // 240s total (increased from 120s for CI reliability)
  for (let idx = 0; idx < maxAttempts; idx++) {
    try {
      // Use minimal retries in fetchData since outer loop handles retry-over-time
      const { state } = await fetchData(url, 2, 2000);
      if (state === 'Ready') {
        console.log(`Node ${name} is ready`);
        return;
      }
      console.log(
        `Node ${name} state: ${state}, waiting ${checkInterval / 1000}s (${idx + 1}/${maxAttempts})`
      );
    } catch (e) {
      console.log(
        `Node ${name} not reachable: ${e.message}, waiting ${checkInterval / 1000}s (${idx + 1}/${maxAttempts})`
      );
    }
    await sleep(checkInterval);
  }

  throw Error(
    `Node ${name} is not ready after ${(checkInterval * maxAttempts) / 1000}s, check the logs.`
  );
};

const validateOrdinalsAndSnapshots = async (urls) => {
  const ordinalsPromises = [];
  for (const url of urls) {
    ordinalsPromises.push(fetchData(`${url}/latest`));
  }
  const ordinals = (await Promise.all(ordinalsPromises)).map((_) => _.value.ordinal);
  ordinals.sort((a, b) => {
    return a - b;
  });

  const lowestOrdinal = ordinals[0];
  const highestOrdinal = ordinals[ordinals.length - 1];
  const differenceBetweenLowestAndHigherOrdinal = highestOrdinal - lowestOrdinal;

  if (differenceBetweenLowestAndHigherOrdinal > 3) {
    throw Error(
      `Ordinals difference greater than 3. Difference: ${differenceBetwenLowestAndHigherOrdinal}`
    );
  }

  const snapshotsPromises = [];
  for (const url of urls) {
    snapshotsPromises.push(fetchData(`${url}/${lowestOrdinal}`));
  }

  const snapshots = (await Promise.all(snapshotsPromises)).map((_) => _.value.lastSnapshotHash);
  const areSnapshotsTheSame = snapshots.every(
    (snapshot) => snapshot === snapshots[0]
  );
  if (!areSnapshotsTheSame) {
    throw Error(
      `Snapshots are different between nodes: ${JSON.stringify(snapshots)}`
    );
  }

  console.log(
    `All snapshots are the same on the ordinal: ${lowestOrdinal}: ${JSON.stringify(
      snapshots
    )}`
  );
};

const assertClusterSize = async (clusterUrl, expectedSize, name) => {
  const clusterInfo = await fetchData(clusterUrl);
  const clusterSize = clusterInfo.length;

  if (clusterSize < expectedSize) {
    throw Error(
      `Cluster ${name} size is less than expected. Actual: ${clusterSize}. Expected: >= ${expectedSize}`
    );
  }

  console.log(`Cluster ${name} with size ${clusterSize} (>= ${expectedSize} expected)`);
};

const clusterCheck = async (
  infos,
  checkOrdinalsAndSnapshots,
  clusterName,
  expectedClusterSize,
  globalLayer
) => {
  try {
    console.log(`Starting to check if nodes are ready: ${clusterName}`);
    const promises = [];
    for (const { baseUrl, name } of infos) {
      promises.push(checkIfNodeIsReady(`${baseUrl}/node/info`, name));
    }
    await Promise.all(promises);
    console.log(`Finished to check if nodes are ready: ${clusterName}`);

    if (checkOrdinalsAndSnapshots) {
      console.log(`Starting to validate ordinals and snapshots: ${clusterName}`);
      console.log(`Waiting 30s before start checking`)
      await sleep(30 * 1000)
      const urls = infos.map(
        (info) =>
          `${info.baseUrl}/${globalLayer ? 'global-snapshots' : 'snapshots'}`
      );
      await validateOrdinalsAndSnapshots(urls);
      console.log(
        `Finished to validate ordinals and snapshots: ${clusterName}`
      );
    }

    console.log(`Starting to validate cluster size: ${clusterName}`);
    await assertClusterSize(
      `${infos[0].baseUrl}/cluster/info`,
      expectedClusterSize,
      clusterName
    );
    console.log(`Finished to validate cluster size: ${clusterName}`);
  } catch (e) {
    console.log(`Error on ${clusterName} nodes`, e.message);
    throw e;
  }
};

const isRemoteHost = () => {
  const host = process.env.TEST_HOST;
  return host && host !== 'http://localhost';
};

const checkGlobalL0Node = async (config) => {
  const { dagL0PortPrefix } = config
  const host = process.env.TEST_HOST || 'http://localhost';
  const gl0Url = process.env.GL0_URL || `${host}:${dagL0PortPrefix}00`;

  if (isRemoteHost()) {
    const infos = [{ name: 'Global L0', baseUrl: gl0Url }];
    await clusterCheck(infos, true, 'Global L0', 1, true);
  } else {
    const infos = [
      { name: 'Global L0 Genesis', baseUrl: `${host}:${dagL0PortPrefix}00` },
      { name: 'Global L0 Validator 1', baseUrl: `${host}:${dagL0PortPrefix}10` },
      { name: 'Global L0 Validator 2', baseUrl: `${host}:${dagL0PortPrefix}20` },
    ];
    await clusterCheck(infos, true, 'Global L0', 3, true);
  }
};

const checkCurrencyL0Node = async (config) => {
  const { metagraphL0PortPrefix } = config
  const host = process.env.TEST_HOST || 'http://localhost';
  const ml0Url = process.env.ML0_URL || `${host}:${metagraphL0PortPrefix}00`;

  if (isRemoteHost()) {
    const infos = [{ name: 'Currency L0', baseUrl: ml0Url }];
    await clusterCheck(infos, true, 'Currency L0', 1, false);
  } else {
    const infos = [
      { name: 'Currency L0 - 1', baseUrl: `${host}:${metagraphL0PortPrefix}00` },
      { name: 'Currency L0 - 2', baseUrl: `${host}:${metagraphL0PortPrefix}10` },
      { name: 'Currency L0 - 3', baseUrl: `${host}:${metagraphL0PortPrefix}20` },
    ];
    await clusterCheck(infos, true, 'Currency L0', 3, false);
  }
};

module.exports = {
    clusterCheck,
    checkGlobalL0Node,
    checkCurrencyL0Node
}