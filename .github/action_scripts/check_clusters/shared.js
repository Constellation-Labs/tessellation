const fetch = require('node-fetch');

const fetchData = async (url) => {
    try {
        const response = await fetch(url, {
            method: 'GET',
            headers: {
                Accept: 'application/json'
            }
        });

        const responseParsed = await response.json();

        if (response.status !== 200) {
            throw Error(`Response status from URL: ${url} is not 200`);
        }

        return responseParsed;
    } catch (e) {
        throw Error(`Error when fetching data: ${e.message}`);
    }
};

const sleep = (ms) => {
    return new Promise((resolve) => setTimeout(resolve, ms));
};

const checkIfNodeIsReady = async (url, name) => {
    console.log(`Checking if ${name} is ready`);
    const checkInterval = 10 * 1000;
    for (let idx = 0; idx < 12; idx++) {
        const { state } = await fetchData(url);
        if (state === 'Ready') {
            console.log(`Node ${name} is ready`);
            return;
        }
        console.log(
            `Node ${name} is not ready yet, waiting ${checkInterval / 1000}s`
        );
        await sleep(checkInterval);
    }

    throw Error(
        `Node ${name} is not ready after ${(checkInterval * 12) / 1000
        }s, check the logs.`
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

    if (clusterSize !== expectedSize) {
        throw Error(
            `Cluster ${name} size is different than expected. Actual: ${clusterSize}. Expected: ${expectedSize}`
        );
    }

    console.log(`Cluster ${name} with expected size of ${expectedSize}`);
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

const checkGlobalL0Node = async () => {
    const infos = [
        {
            name: 'Global L0 Genesis',
            baseUrl: 'http://localhost:9000'
        },
        {
            name: 'Global L0 Validator 1',
            baseUrl: 'http://localhost:8000'
        },
        {
            name: 'Global L0 Validator 2',
            baseUrl: 'http://localhost:8100'
        }
    ];
    await clusterCheck(infos, true, 'Global L0', 3, true);
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

module.exports = {
    clusterCheck,
    checkGlobalL0Node,
    checkCurrencyL0Node
}