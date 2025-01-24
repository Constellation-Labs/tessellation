const axios = require('axios');
const { parseSharedArgs } = require('./shared');

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
  const snapshotUrl = `http://localhost:${metagraphL0PortPrefix}00/snapshots/latest`

  const { value } = await fetchData(snapshotUrl)
  if (!value) {
    throw Error(`Could not get value from snapshot`)
  }

  const { rewards } = value

  if (!rewards || rewards.length !== 2) {
    throw Error(`Fail on rewards distribution: ${JSON.stringify(rewards)}`)
  }

  const firstReward = rewards[0]
  const secondReward = rewards[1]

  if (firstReward.amount !== 555000000 || secondReward.amount !== 555000000) {
    throw Error(`Fail with rewards amount: ${JSON.stringify(firstReward)} - ${JSON.stringify(secondReward)}`)
  }

  console.log(`All rewards were successfully distributed: ${JSON.stringify(rewards)}`)
}

main()