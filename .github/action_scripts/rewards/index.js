const fetch = require('node-fetch');

const fetchData = async (url) => {
  try {
    const response = await fetch(url, {
      method: 'GET',
      headers: {
        Accept: 'application/json'
      }
    })

    const responseParsed = await response.json()

    if (response.status !== 200) {
      throw Error(`Response status from URL: ${url} is not 200`)
    }

    return responseParsed
  } catch (e) {
    throw Error(`Error when fetching data: ${e.message}`)
  }
};

const main = async () => {
  const snapshotUrl = "http://localhost:9400/snapshots/latest"

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