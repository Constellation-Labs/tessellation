const axios = require('axios')
const elliptic = require('elliptic')
const fs = require('fs')

const {
  sleep,
  withRetry,
  generateProof,
  SerializerType,
  logWorkflow,
} = require('../shared')

const checkOk = (response) => {
  if (response.status !== 200) {
    throw new Error(`Node returned ${response.status} instead of 200`)
  }
}

const checkBadRequest = (response) => {
  if (response.status !== 400) {
    throw new Error(`Node returned ${response.status} instead of 400`)
  }
}

const dagToDatum = (dag) => {
  return Math.round(dag * 1e8)
}

function getPrivateKeyAndNodeIdFromFile(filePath) {
  const privateKeyHex = fs.readFileSync(filePath, 'utf8').trim()

  const privateKeyBuffer = Buffer.from(privateKeyHex, 'hex')

  try {
    const ec = new elliptic.ec('secp256k1')

    const privateKeyString = privateKeyBuffer.toString('hex')
    const keyPair = ec.keyFromPrivate(privateKeyBuffer)

    const uncompressedPublicKey = keyPair.getPublic(false, 'hex') // Uncompressed format
    const nodeId = uncompressedPublicKey.slice(2) // Remove the '0x04' prefix

    return { privateKeyString, nodeId }
  } catch (error) {
    console.error('Error processing the private key:', error)
    throw error
  }
}

const postNodeParamsNodeId = async (
  urls,
  nodeId,
  account,
  privateKeyString,
  parametersName,
  rewardFraction,
) => {
  let parent = {
    ordinal: 0,
    hash: '0000000000000000000000000000000000000000000000000000000000000000',
  }

  try {
    const response = await axios.get(
      `${urls.globalL0Url}/node-params/${nodeId}?t=${Date.now()}`,
      {
        headers: {
          'Cache-Control': 'no-cache, no-store, must-revalidate',
          Pragma: 'no-cache',
          Expires: '0',
        },
      },
    )
    if (response.status === 200 && response.data) {
      parent = response.data.lastRef
    }
  } catch (error) {
    // NOOP
  }

  const unsignedNodeParams = {
    source: account.address,
    delegatedStakeRewardParameters: {
      rewardFraction: rewardFraction,
    },
    nodeMetadataParameters: {
      name: parametersName,
      description: parametersName,
    },
    parent: parent,
  }

  const proof = await generateProof(
    unsignedNodeParams,
    privateKeyString,
    account,
    SerializerType.BROTLI,
  )
  const content = { value: unsignedNodeParams, proofs: [{ ...proof }] }

  try {
    const updateResponse = await axios.post(
      `${urls.globalL0Url}/node-params`,
      content,
    )
    await sleep(2000)
    return updateResponse
  } catch (error) {
    if (axios.isAxiosError(error)) {
      return error.response
    } else {
      throw error
    }
  }
}

const createDelegatedStake = async (account, lockHash, lockAmount, nodeId) => {
  const { hash } = await account.postDelegatedStake({
    source: account.address,
    nodeId: nodeId,
    amount: lockAmount,
    fee: 0,
    tokenLockRef: lockHash,
  })

  return hash
}

const withdrawDelegatedStake = async (account, stakeHash) => {
  const { hash } = await account.putWithdrawDelegatedStake({
    source: account.address,
    stakeRef: stakeHash,
  })

  return hash
}

const getAccountDelegatedStakes = async (urls, address) => {
  const response = await axios.get(
    `${urls.globalL0Url}/delegated-stakes/${address}/info?t=${Date.now()}`,
    {
      headers: {
        'Cache-Control': 'no-cache, no-store, must-revalidate',
        Pragma: 'no-cache',
        Expires: '0',
      },
    },
  )
  checkOk(response)
  return response.data
}

// Assert at least the passed keys are present in the array of objects
const assertAllKeysMatch = (arr, obj) => {
  const isValid = arr.some((item) =>
    Object.entries(obj).every(([key, value]) => item[key] === value),
  )

  if (!isValid) {
    // logWorkflow.info(JSON.stringify(arr))
    throw new Error(
      `Expected all keys to be present in response: ${JSON.stringify(obj)}`,
    )
  }
}

const assertDelegatedStakes = (stakeResponse, activeStakes, pendingStakes) => {
  const expectedActiveLength = activeStakes.length
  const actualActiveLength = stakeResponse.activeDelegatedStakes.length
  if (expectedActiveLength !== actualActiveLength) {
    throw new Error(
      `Expected ${expectedActiveLength} active stakes but got ${actualActiveLength}`,
    )
  }

  Object.values(activeStakes).map((stakeItem) => {
    assertAllKeysMatch(stakeResponse.activeDelegatedStakes, stakeItem)
  })

  const expectedPendingLength = pendingStakes.length
  const actualPendingLength = stakeResponse.pendingWithdrawals.length
  if (expectedPendingLength !== actualPendingLength) {
    throw new Error(
      `Expected ${expectedPendingLength} active stakes but got ${actualPendingLength}`,
    )
  }

  Object.values(pendingStakes).map((stakeItem) => {
    assertAllKeysMatch(stakeResponse.pendingWithdrawals, stakeItem)
  })
}

// Get stake to update and wait until it has some rewards
const fetchStakeWithRewardsBalance = async (
  urls,
  address,
  stakeHash,
  nodeId = null,
) => {
  return withRetry(
    async () => {
      const stakeResponse = await getAccountDelegatedStakes(urls, address)
      const stake = stakeResponse.activeDelegatedStakes.find(
        (stake) => stake.hash === stakeHash && stake.rewardAmount > 0,
      )

      if (!stake) {
        throw new Error('Stake not found with rewards balance')
      }

      const stakeAlreadyExists = stakeResponse.activeDelegatedStakes.find(
        (stake) => {
          return (
            (nodeId ? stake.nodeId === nodeId : true) &&
            address === stake.source
          )
        },
      )

      if (stakeAlreadyExists) {
        throw new Error('Cant update, stake already exists')
      }

      return stake
    },
    {
      name: 'FetchStakeWithRewardsBalance',
      maxAttempts: 20,
      interval: 5 * 1000,
      handleError: () => {},
    },
  )
}

const createTokenLock = async (account, urls, lockAmount) => {
  const initialBalance = dagToDatum(await account.getBalance())

  const { hash } = await account.postTokenLock({
    source: account.address,
    amount: lockAmount,
    tokenL1Url: urls.dagL1Url,
    unlockEpoch: null,
    currencyId: null,
    fee: 0,
  })

  if (!hash) {
    throw new Error('Failed to create TokenLock')
  }

  await withRetry(
    async () => assertBalanceChange(account, initialBalance - lockAmount),
    {
      name: 'assertBalanceChangeAfterTokenLock',
      maxAttempts: 10,
      interval: 1000,
      handleError: () => {},
    },
  )

  return hash
}

const assertBalanceChange = async (account, expectedBalanceDatum) => {
  const balance = dagToDatum(await account.getBalance())

  if (balance !== expectedBalanceDatum) {
    throw new Error(
      `Invalid balance: Expected balance to be ${expectedBalanceDatum} but got ${balance}`,
    )
  }
}

const getNodeParams = async (urls) => {
  logWorkflow.info(`Request to: ${urls.globalL0Url}/node-params`)
  const response = await axios.get(
    `${urls.globalL0Url}/node-params?t=${Date.now()}`,
    {
      headers: {
        'Cache-Control': 'no-cache, no-store, must-revalidate',
        Pragma: 'no-cache',
        Expires: '0',
      },
    },
  )
  checkOk(response)
  return response.data
}

const fetchSnapshot = async (urls, ordinal) => {
  logWorkflow.info(`Fetching snapshot: ${ordinal} `)

  const response = await axios.get(
    `${urls.globalL0Url}/global-snapshots/${ordinal}?t=${Date.now()}`,
    {
      headers: {
        'Cache-Control': 'no-cache, no-store, must-revalidate',
        Pragma: 'no-cache',
        Expires: '0',
      },
    },
  )
  checkOk(response)
  return response.data
}

const assertRewardTxnInSnapshot = async (snapshot, account, amount) => {
  const rewardTxn = snapshot.value.rewards.find((txn) => {
    return txn.amount === amount && txn.destination === account.address
  })

  if (!rewardTxn) {
    throw new Error('Reward txn not found for withdrawal')
  }
}

const assertTokenUnlockInSnapshot = async (
  snapshot,
  account,
  lockHash,
  amount,
) => {
  const tokenUnlock = snapshot.value.artifacts.find((item) => {
    return (
      item.hasOwnProperty('TokenUnlock') &&
      item.TokenUnlock.tokenLockRef === lockHash &&
      item.TokenUnlock.amount === amount &&
      item.TokenUnlock.source === account.address
    )
  })

  if (!tokenUnlock) {
    throw new Error('TokenUnlock not found for withdrawal')
  }
}

module.exports = {
  checkOk,
  checkBadRequest,
  dagToDatum,
  getPrivateKeyAndNodeIdFromFile,
  postNodeParamsNodeId,
  createDelegatedStake,
  withdrawDelegatedStake,
  getAccountDelegatedStakes,
  assertAllKeysMatch,
  assertDelegatedStakes,
  fetchStakeWithRewardsBalance,
  createTokenLock,
  assertBalanceChange,
  getNodeParams,
  fetchSnapshot,
  assertRewardTxnInSnapshot,
  assertTokenUnlockInSnapshot
}
