const axios = require('axios');
const { parseSharedArgs, withRetry } = require('./shared');

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


const fetchData = async (url) => {
  try {
    const response = await axios.get(url, {
      headers: {
        Accept: 'application/json'
      }
    })

    const responseParsed = response.data

    if (response.status !== 200) {
      throw Error(`Response status from URL: ${url} is not 200`)
    }

    return responseParsed
  } catch (e) {
    throw Error(`Error when fetching data: ${e.message}`)
  }
};

const main = async () => {
  const { metagraphL0PortPrefix } = createConfig()
  const host = process.env.TEST_HOST || 'http://localhost';
  const snapshotUrl = process.env.ML0_URL ? `${process.env.ML0_URL}/snapshots/latest` : `${host}:${metagraphL0PortPrefix}00/snapshots/latest`

  // Cluster health only proves that the ML0 nodes have joined. The latest snapshot can still be
  // genesis (or the first post-genesis snapshot), neither of which is required to carry the
  // configured reward distribution. Wait for an actually finalized reward-bearing snapshot so
  // startup speed cannot turn this assertion into a race.
  const { ordinal, rewards } = await withRetry(async () => {
    const { value } = await fetchData(snapshotUrl)
    if (!value) {
      throw Error(`Could not get value from snapshot`)
    }

    const rewards = value.rewards || []
    const hasExpectedDistribution =
      rewards.length === 2 && rewards.every(({ amount }) => amount === 555000000)

    if (!hasExpectedDistribution) {
      throw Error(
        `Rewards not finalized at ordinal ${value.ordinal}: ${JSON.stringify(rewards)}`
      )
    }

    return { ordinal: value.ordinal, rewards }
  }, {
    name: 'metagraph rewards distribution',
    maxAttempts: 120,
    interval: 1000,
  })

  console.log(
    `All rewards were successfully distributed at ordinal ${ordinal}: ${JSON.stringify(rewards)}`
  )
}

main()
