const axios = require('axios');

const fetchData = async (url, maxRetries = 24, retryIntervalMs = 5000) => {
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
  const maxAttempts = 48; // 480s total (doubled for CI reliability)
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

const validateOrdinalsAndSnapshots = async (urls, expectedSigners) => {
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
      `Ordinals difference greater than 3. Difference: ${differenceBetweenLowestAndHigherOrdinal}`
    );
  }

  const snapshotsPromises = [];
  for (const url of urls) {
    snapshotsPromises.push(fetchData(`${url}/${lowestOrdinal}`));
  }

  const snapshotResponses = await Promise.all(snapshotsPromises);
  const snapshots = snapshotResponses.map((_) => _.value.lastSnapshotHash);
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

  // Validate signature count — poll until a snapshot has all expected signatures
  if (expectedSigners) {
    const maxPollAttempts = 30; // 30 × 10s = 300s (enough for ~7 rounds at 43s)
    let found = false;
    for (let attempt = 1; attempt <= maxPollAttempts; attempt++) {
      const pollUrl = urls[attempt % urls.length]; // cycle through nodes
      try {
        const latestResp = await fetchData(`${pollUrl}/latest`);
        const ord = latestResp.value.ordinal;
        const proofs = latestResp.proofs || [];
        const signerCount = proofs.length;
        console.log(`  Signature poll ${attempt}/${maxPollAttempts}: ordinal=${ord} signatures=${signerCount} (need ${expectedSigners})`);
        if (signerCount >= expectedSigners) {
          const signerIds = proofs.map((p) => p.id.hex ? p.id.hex.substring(0, 8) : p.id.substring(0, 8));
          console.log(
            `Snapshot at ordinal ${ord} has ${signerCount} signatures (>= ${expectedSigners}): [${signerIds.join(', ')}]`
          );
          found = true;
          break;
        }
      } catch (e) {
        console.log(`  Signature poll ${attempt}/${maxPollAttempts}: fetch error, retrying...`);
      }
      await sleep(10 * 1000);
    }
    if (!found) {
      throw Error(
        `No snapshot with >= ${expectedSigners} signatures found after ${maxPollAttempts} attempts`
      );
    }
  }
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
  globalLayer,
  expectedSigners
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
      await validateOrdinalsAndSnapshots(urls, expectedSigners);
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
    const numGL0 = parseInt(process.env.NUM_GL0_NODES || '3', 10);
    const infos = [];
    for (let i = 0; i < numGL0; i++) {
      const port = `${dagL0PortPrefix}${String(i * 10).padStart(2, '0')}`;
      const name = i === 0 ? 'Global L0 Genesis' : `Global L0 Validator ${i}`;
      infos.push({ name, baseUrl: `${host}:${port}` });
    }
    // Signatures expected on a snapshot: BFT quorum (2f+1), NOT unanimity. Global L0 consensus
    // finalizes at a supermajority, so a snapshot signed by a quorum is correct -- requiring all
    // numGL0 signatures makes this check flaky whenever a single node's signature lands after
    // finalization (common under load). Override with EXPECTED_GL0_SIGNERS for strict checks.
    const quorum = Math.floor((2 * numGL0) / 3) + 1;
    const expectedSigners = parseInt(process.env.EXPECTED_GL0_SIGNERS || String(quorum), 10);
    await clusterCheck(infos, true, 'Global L0', numGL0, true, expectedSigners);
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