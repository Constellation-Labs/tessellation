const axios = require('axios')
const elliptic = require('elliptic')
const fs = require('fs')
const path = require('path')

const {
  sleep,
  withRetry,
  withRetryOrdinal,
  waitForTxInclusion,
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

// Resolve a node operator's key file across environments, in priority order:
// 1. NODE_KEYS_DIR env var (set by the nightly E2E workflow); layout <dir>/<index>/id_ecdsa.hex.
// 2. CI Euclid cluster keys staged under ../../code/hypergraph/dag-l0/<name>/id_ecdsa.hex.
// 3. The bundled keys/ fixtures (genesis-node.hex, validator-N-node.hex) for local runs.
const resolveNodeKeyPath = (name, index) => {
  const runEnv = process.env.RUN_ENV || 'ci'
  const keysDir = process.env.NODE_KEYS_DIR
  if (keysDir) {
    const resolved = path.isAbsolute(keysDir)
      ? keysDir
      : path.resolve(__dirname, '../../..', keysDir)
    return path.join(resolved, String(index), 'id_ecdsa.hex')
  }
  if (runEnv === 'ci') {
    return `../../code/hypergraph/dag-l0/${name}/id_ecdsa.hex`
  }
  const localFile = name === 'genesis-node' ? 'genesis-node.hex' : `${name}-node.hex`
  return path.join(__dirname, 'keys', localFile)
}

function getPrivateKeyAndNodeIdFromFile(filePath) {
  let privateKeyHex
  try {
    privateKeyHex = fs.readFileSync(filePath, 'utf8').trim()
  } catch (error) {
    throw new Error(
      `Unable to read node key file at "${filePath}" (resolved from cwd "${process.cwd()}"): ${error.message}. ` +
        'Check NODE_KEYS_DIR / RUN_ENV and that the cluster keys were staged.',
    )
  }

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
      maxAttempts: 40,
      interval: 5 * 1000,
      handleError: () => {},
    },
  )
}

const createTokenLock = async (account, urls, lockAmount, replaceRef = null, replaceBalance = 0) => {
  const initialBalance = dagToDatum(await account.getBalance())

  const { hash } = await account.postTokenLock({
    source: account.address,
    amount: lockAmount,
    tokenL1Url: urls.dagL1Url,
    unlockEpoch: null,
    currencyId: null,
    replaceTokenLockRef: replaceRef,
    fee: 0,
  })

  if (!hash) {
    throw new Error('Failed to create TokenLock')
  }

  // The account may hold active delegated stakes that accrue reward credits during
  // the wait, so its balance can sit ABOVE the exact post-lock value (rewards only
  // ever add). Require the balance to have dropped by ~the locked amount (confirming
  // the lock applied) while tolerating upward drift from accrued rewards, plus a small
  // rounding slack for 1-datum discrepancies seen in reward math.
  const expectedAfterLock = initialBalance - lockAmount + replaceBalance
  const lockDelta = lockAmount - replaceBalance
  const rewardTolerance = Math.max(1, Math.floor(lockDelta / 2))
  const roundingSlack = 10
  await withRetry(
    async () => {
      const balance = dagToDatum(await account.getBalance())
      if (balance < expectedAfterLock - roundingSlack || balance > expectedAfterLock + rewardTolerance) {
        throw new Error(
          `Balance after token lock = ${balance}, expected within [${expectedAfterLock}, ${expectedAfterLock + rewardTolerance}] (tolerating accrued rewards)`,
        )
      }
    },
    {
      name: 'assertBalanceChangeAfterTokenLock',
      maxAttempts: 60,
      interval: 2000,
      handleError: () => {},
    },
  )

  return hash
}

const assertBalanceChange = async (account, expectedBalanceDatum, tolerance = 0) => {
  const balance = dagToDatum(await account.getBalance())

  if (Math.abs(balance - expectedBalanceDatum) > tolerance) {
    throw new Error(
      `Invalid balance: Expected balance to be ${expectedBalanceDatum}${tolerance ? ` (±${tolerance})` : ''} but got ${balance}`,
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

/**
 * Wait for a delegated stake to appear in activeDelegatedStakes using ordinal-aware retry.
 * This is more robust than wall-clock retries because it detects dropped transactions.
 * 
 * @param {Object} urls - Network URLs including globalL0Url
 * @param {string} address - Account address
 * @param {string} stakeHash - Expected stake hash
 * @param {Object} [options] - Additional options for withRetryOrdinal
 * @returns {Promise<Object>} - The stake object when found
 */
const waitForStakeInclusion = async (urls, address, stakeHash, options = {}) => {
  return withRetryOrdinal(
    async () => {
      const response = await getAccountDelegatedStakes(urls, address)
      const stake = response.activeDelegatedStakes.find(s => s.hash === stakeHash)
      if (!stake) {
        throw new Error(`Stake ${stakeHash.substring(0, 16)}... not in activeDelegatedStakes`)
      }
      return stake
    },
    {
      globalL0Url: urls.globalL0Url,
      name: 'waitForStakeInclusion',
      maxOrdinalMisses: 10,
      maxStalledChecks: 30,
      interval: 2000,
      ...options
    }
  )
}

/**
 * Wait for a delegated stake to move to pendingWithdrawals using ordinal-aware retry.
 * 
 * @param {Object} urls - Network URLs including globalL0Url
 * @param {string} address - Account address
 * @param {string} stakeHash - Expected stake hash
 * @param {Object} [options] - Additional options for withRetryOrdinal
 * @returns {Promise<Object>} - The pending withdrawal object when found
 */
const waitForStakeWithdrawal = async (urls, address, stakeHash, options = {}) => {
  return withRetryOrdinal(
    async () => {
      const response = await getAccountDelegatedStakes(urls, address)
      const pending = response.pendingWithdrawals.find(s => s.hash === stakeHash)
      if (!pending) {
        throw new Error(`Stake ${stakeHash.substring(0, 16)}... not in pendingWithdrawals`)
      }
      return pending
    },
    {
      globalL0Url: urls.globalL0Url,
      name: 'waitForStakeWithdrawal',
      maxOrdinalMisses: 10,
      maxStalledChecks: 30,
      interval: 2000,
      ...options
    }
  )
}

/**
 * Wait for a token lock to appear in account's active token locks using ordinal-aware retry.
 * 
 * @param {Object} urls - Network URLs including globalL0Url  
 * @param {string} address - Account address
 * @param {string} lockHash - Expected lock hash
 * @param {Object} [options] - Additional options for withRetryOrdinal
 * @returns {Promise<Object>} - The token lock object when found
 */
const waitForTokenLockInclusion = async (urls, address, lockHash, options = {}) => {
  return withRetryOrdinal(
    async () => {
      const response = await axios.get(
        `${urls.globalL0Url}/token-locks/${address}?t=${Date.now()}`,
        {
          headers: {
            'Cache-Control': 'no-cache, no-store, must-revalidate',
            Pragma: 'no-cache',
            Expires: '0',
          },
        }
      )
      checkOk(response)
      const lock = response.data.find(l => l.hash === lockHash)
      if (!lock) {
        throw new Error(`Token lock ${lockHash.substring(0, 16)}... not found`)
      }
      return lock
    },
    {
      globalL0Url: urls.globalL0Url,
      name: 'waitForTokenLockInclusion',
      maxOrdinalMisses: 10,
      maxStalledChecks: 30,
      interval: 2000,
      ...options
    }
  )
}

/**
 * Get active token locks for an address from GL0.
 * 
 * @param {Object} urls - Network URLs including globalL0Url
 * @param {string} address - Account address
 * @returns {Promise<Array>} - Array of active token lock objects
 */
const getActiveTokenLocks = async (urls, address) => {
  const response = await axios.get(
    `${urls.globalL0Url}/token-locks/${address}?t=${Date.now()}`,
    {
      headers: {
        'Cache-Control': 'no-cache, no-store, must-revalidate',
        Pragma: 'no-cache',
        Expires: '0',
      },
    }
  )
  checkOk(response)
  return response.data || []
}

module.exports = {
  checkOk,
  checkBadRequest,
  dagToDatum,
  getPrivateKeyAndNodeIdFromFile,
  resolveNodeKeyPath,
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
  assertTokenUnlockInSnapshot,
  // Ordinal-aware helpers
  waitForStakeInclusion,
  waitForStakeWithdrawal,
  waitForTokenLockInclusion,
  getActiveTokenLocks,
}
