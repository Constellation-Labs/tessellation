/**
 * Delegated Staking tests - run with CI or local (Euclid)
 * 
 * Local run instructions:
 * - Start Euclid from genesis (`hydra start-genesis`)
 * - RUN_ENV=local node .github/action_scripts/delegated_staking/delegated-staking 90 91 testDelegatedStaking
 * - Reset Euclid to run again (`hydra stop && hydra start-genesis`)
 */

const { dag4 } = require('@stardust-collective/dag4')
const axios = require('axios')
const fs = require('fs')
const path = require('path')
const elliptic = require('elliptic')

const RUN_ENV = process.env.RUN_ENV || 'ci';

const {
  parseSharedArgs,
  CONSTANTS: sharedConstants,
  PRIVATE_KEYS,
  sleep,
  withRetry,
  generateProof,
  SerializerType,
  createNetworkConfig,
  logWorkflow,
} = require('../shared')

const throwUsage = () => {
  throw new Error(
    'Usage: node script.js <dagl0-port-prefix> <dagl1-port-prefix> <workflow-name>',
  )
}

const createConfig = () => {
  const args = process.argv.slice(2)

  if (args.length < 3) {
    return throwUsage()
  }

  const sharedArgs = parseSharedArgs(args.slice(0, 3), false)
  return { ...sharedArgs }
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
  }
}

const createNodeParams = async (
  account,
  parametersName,
  rewardFraction,
  parent,
) => {
    return {
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
}

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
  return Math.round(dag * 1e8);
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

const verifyInitialNodeParams = (response) => {
  if (response.length) {
    throw new Error(
      `Initial node parameters should be empty but received ${response.length}`,
    )
  }
}

const extractKeysAndAccount = (filePath) => {
  const { privateKeyString, nodeId } = getPrivateKeyAndNodeIdFromFile(filePath)
  
  const account = dag4.createAccount(privateKeyString)
  
  return { privateKeyString, nodeId, account }
}

const postNodeParamsNodeId = async (
  urls,
  nodeId,
  account,
  privateKeyString,
  parameterName,
  rewardFaction,
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

  const unsignedNodeParams = await createNodeParams(
    account,
    parameterName,
    rewardFaction,
    parent,
  )
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

const checkInitialNodeParamsNode = async (urls, nodeId) => {
  try {
    await axios.get(
      `${urls.globalL0Url}/node-params/${nodeId}?t=${Date.now()}`,
      {
        headers: {
          'Cache-Control': 'no-cache, no-store, must-revalidate',
          Pragma: 'no-cache',
          Expires: '0',
        },
      },
    )
    throw new Error(
      `Initial ${urls.globalL0Url}/node-params/${nodeId} shal not be defined`,
    )
  } catch (error) {
    // 404 expected, NOOP
  }
}

const verifyNodeParamsResponse = (
  nodeParams,
  nodeId,
  expectedName,
  expectedRewardFraction,
) => {
  const data = nodeParams.find((item) => item.peerId === nodeId)
  if (!data) throw new Error(`PeerId is not correct`)
  if (data.nodeMetadataParameters.name !== expectedName)
    throw new Error(
      `Node parameters name expected ${expectedName} but received ${data.nodeMetadataParameters.name}`,
    )
  if (
    data.delegatedStakeRewardParameters.rewardFraction !==
    expectedRewardFraction
  )
    throw new Error(
      `Node parameters rewardFraction expected ${expectedRewardFraction} but received ${data.delegatedStakeRewardParameters.rewardFraction}`,
    )
  if (data.node && data.node.id != nodeId)
    throw new Error(`Node id is not correct`)
}

const getNodeParamsNodeIdVerify = async (
  urls,
  nodeId,
  expectedName,
  expectedRewardFraction,
  expectedOrdinal,
) => {
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
  if (response.status !== 200)
    throw new Error(`NodeParamsNode returned ${response.status} instead of 200`)
  const receivedRewardFraction =
    response.data.latest.value.delegatedStakeRewardParameters.rewardFraction
  if (receivedRewardFraction !== expectedRewardFraction)
    throw new Error(
      `Node parameters node rewardFraction expected ${expectedRewardFraction} but received ${receivedRewardFraction}`,
    )

  const receivedName = response.data.latest.value.nodeMetadataParameters.name
  if (receivedName !== expectedName) {
    throw new Error(
      `Node parameters node name expected ${expectedName} but received ${receivedName}`,
    )
  }

  const receivedOrdinal = response.data.latest.value.parent.ordinal
  if (receivedOrdinal !== expectedOrdinal) {
    throw new Error(
      `Node parameters node name expected expected 0 ordinal but received ${receivedOrdinal}`,
    )
  }
}

const firstNodeParameterName1 = 'FirstNode1'
const firstNodeFraction1 = 10000000

const firstNodeParameterName2 = 'FirstNode2'
const firstNodeFraction2 = 5000000

const secondNodeParameterName1 = 'SecondNode1'
const secondNodeFraction1 = 6000000

const checkCreateNodeParameters = async (urls) => {
  logWorkflow.info('---- Start checkCreateNodeParameters ----')
  const initialNodeParams = await getNodeParams(urls)
  verifyInitialNodeParams(initialNodeParams)
  logWorkflow.info('Initial node params is OK')

  const {
    privateKeyString: privateKeyString1,
    nodeId: nodeId1,
    account: account1,
  } = extractKeysAndAccount(
    RUN_ENV === 'ci' ?
        '../../code/hypergraph/dag-l0/genesis-node/id_ecdsa.hex' :
        path.join(__dirname, 'keys', 'genesis-node.hex')
  )

  await checkInitialNodeParamsNode(urls, nodeId1)
  logWorkflow.info('Check initial node params is OK')

  const ur1 = await postNodeParamsNodeId(
    urls,
    nodeId1,
    account1,
    privateKeyString1,
    firstNodeParameterName1,
    firstNodeFraction1,
  )
  checkOk(ur1)
  logWorkflow.info('Update node params is OK')

  const nodeParamsAfterUpdate = await getNodeParams(urls)
  verifyNodeParamsResponse(
    nodeParamsAfterUpdate,
    nodeId1,
    firstNodeParameterName1,
    firstNodeFraction1,
  )
  logWorkflow.info('Check updates node params is OK')

  await getNodeParamsNodeIdVerify(
    urls,
    nodeId1,
    firstNodeParameterName1,
    firstNodeFraction1,
    0,
  )
  logWorkflow.info('Check updates node params node is OK')

  const ur2 = await postNodeParamsNodeId(
    urls,
    nodeId1,
    account1,
    privateKeyString1,
    firstNodeParameterName2,
    firstNodeFraction2,
  )
  checkOk(ur2)
  logWorkflow.info('Update node params second time is OK')

  const nodeParamsAfterSecondUpdate = await getNodeParams(urls)
  verifyNodeParamsResponse(
    nodeParamsAfterSecondUpdate,
    nodeId1,
    firstNodeParameterName2,
    firstNodeFraction2,
  )
  logWorkflow.info('Check second updates node params is OK')

  await getNodeParamsNodeIdVerify(
    urls,
    nodeId1,
    firstNodeParameterName2,
    firstNodeFraction2,
    1,
  )
  logWorkflow.info('Check second updates node params node is OK')

  //Send incorrect amount
  const ur3 = await postNodeParamsNodeId(
    urls,
    nodeId1,
    account1,
    privateKeyString1,
    firstNodeParameterName2,
    10000001,
  )
  checkBadRequest(ur3)

  await getNodeParamsNodeIdVerify(
    urls,
    nodeId1,
    firstNodeParameterName2,
    firstNodeFraction2,
    1,
  )
  logWorkflow.info('Check updating node with incorrect params is OK')

  const {
    privateKeyString: privateKeyString2,
    nodeId: nodeId2,
    account: account2,
  } = extractKeysAndAccount(
    RUN_ENV === 'ci' ?
        '../../code/hypergraph/dag-l0/validator-1/id_ecdsa.hex' :
        path.join(__dirname, 'keys', 'validator-1-node.hex')
  )

  const ur4 = await postNodeParamsNodeId(
    urls,
    nodeId2,
    account2,
    privateKeyString2,
    secondNodeParameterName1,
    secondNodeFraction1,
  )
  checkOk(ur4)
  
  // tends to fail here in CI, wait a little longer
  await sleep(5000)
  
  await getNodeParamsNodeIdVerify(
    urls,
    nodeId2,
    secondNodeParameterName1,
    secondNodeFraction1,
    0,
  )
  logWorkflow.info('Update second node params is OK')

  const bothNodesParams = await getNodeParams(urls)
  verifyNodeParamsResponse(
    bothNodesParams,
    nodeId1,
    firstNodeParameterName2,
    firstNodeFraction2,
  )
  verifyNodeParamsResponse(
    bothNodesParams,
    nodeId2,
    secondNodeParameterName1,
    secondNodeFraction1,
  )
  logWorkflow.info('Both nodes check is OK')

  logWorkflow.info('---- End checkCreateNodeParameters ----')
}

const setupDag4Account = (urls) => {
  dag4.account.connect({
    networkVersion: '2.0',
    l0Url: urls.globalL0Url,
    l1Url: urls.dagL1Url,
  })

  return dag4.account
}

const assertBalanceChange = async (account, expectedBalanceDatum) => {
  const balance = dagToDatum(await account.getBalance())

  if (balance !== expectedBalanceDatum) {
    throw new Error(
      `Invalid balance: Expected balance to be ${expectedBalanceDatum} but got ${balance}`,
    )
  }
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
    async () =>
      assertBalanceChange(account, initialBalance - lockAmount),
    {
      name: 'assertBalanceChangeAfterTokenLock',
      maxAttempts: 10,
      interval: 1000,
      handleError: () => {},
    },
  )

  return hash
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

const checkCreateDelegatedStake = async (urls, account, nodeId) => {
  logWorkflow.info('---- Start checkCreateDelegatedStake ----')

  const lockAmount = 500000000000
  const lockHash = await createTokenLock(account, urls, lockAmount)

  const stakeResponse = await getAccountDelegatedStakes(urls, account.address)
  assertDelegatedStakes(stakeResponse, [], [])
  logWorkflow.info('Initial stakes are empty')

  const stakeHash = await createDelegatedStake(
    account,
    lockHash,
    lockAmount,
    nodeId,
  )
  logWorkflow.info('Stake created')

  await withRetry(
    async () => {
      const updatedStakeResponse = await getAccountDelegatedStakes(
        urls,
        account.address,
      )
      return assertDelegatedStakes(
        updatedStakeResponse,
        [
          {
            hash: stakeHash,
            nodeId,
            amount: lockAmount,
          },
        ],
        [],
      )
    },
    {
      name: 'assertDelegatedStakeCreated',
      maxAttempts: 10,
      interval: 1000,
      handleError: () => {},
    },
  )
  logWorkflow.info('Stake creation verified')

  logWorkflow.info('---- End checkCreateDelegatedStake ----')

  return stakeHash
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

const checkUpdateDelegatedStake = async (urls, account, stakeHash, nodeId) => {
  logWorkflow.info('---- Start checkUpdateDelegatedStake ----')

  logWorkflow.info('Waiting for stake with non-zero rewards balance')

  const originalStake = await fetchStakeWithRewardsBalance(
    urls,
    account.address,
    stakeHash,
    nodeId,
  )

  if (!originalStake) {
    throw new Error('Stake not found, cannot test updating stake')
  }

  if (nodeId === originalStake.nodeId) {
    throw new Error('Cannot update to the same node')
  }

  const updatedStakeHash = await createDelegatedStake(
    account,
    originalStake.tokenLockRef,
    originalStake.amount,
    nodeId,
  )
  logWorkflow.info('Stake updated')

  // new stake in activeDelegatedStakes with updated values, balance transfers
  // old stake removed (not in active or pendingWithdrawal)
  await withRetry(
    async () => {
      const updatedStakeResponse = await getAccountDelegatedStakes(
        urls,
        account.address,
      )
      return assertDelegatedStakes(
        updatedStakeResponse,
        [
          {
            hash: updatedStakeHash,
            nodeId,
            amount: originalStake.amount,
            tokenLockRef: originalStake.tokenLockRef,
            rewardAmount: originalStake.rewardAmount, // balance is transferred
          },
        ],
        [],
      )
    },
    {
      name: 'assertDelegatedStakeUpdated',
      maxAttempts: 10,
      interval: 1000,
      handleError: () => {},
    },
  )
  logWorkflow.info('Stake update verified with balance change')

  logWorkflow.info('---- End checkUpdateDelegatedStake ----')

  return updatedStakeHash
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
  rewardTxn = snapshot.value.rewards.find((txn) => {
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
  tokenUnlock = snapshot.value.artifacts.find((item) => {
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

const checkWithdrawDelegatedStake = async (urls, account, stakeHash) => {
  logWorkflow.info('---- Start checkWithdrawDelegatedStake ----')

  const initialBalance = dagToDatum(await account.getBalance())

  const originalStake = await fetchStakeWithRewardsBalance(
    urls,
    account.address,
    stakeHash,
  )

  if (!originalStake) {
    throw new Error('Stake not found, cannot test updating stake')
  }

  await withdrawDelegatedStake(account, stakeHash)
  logWorkflow.info('Stake withdrawal sent')

  // stake record moves to pendingWithdrawals, balance same as last active
  await withRetry(
    async () => {
      const updatedStakeResponse = await getAccountDelegatedStakes(
        urls,
        account.address,
      )
      return assertDelegatedStakes(
        updatedStakeResponse,
        [],
        [
          {
            hash: stakeHash,
            nodeId: originalStake.nodeId,
            amount: originalStake.amount,
            tokenLockRef: originalStake.tokenLockRef,
            rewardAmount: originalStake.rewardAmount,
            totalBalance: originalStake.totalBalance,
          },
        ],
      )
    },
    {
      name: 'assertDelegatedStakeMovedToPending',
      maxAttempts: 10,
      interval: 1000,
      handleError: () => {},
    },
  )
  logWorkflow.info('Stake withdraw verified pending')

  // TODO: need a way to speed this up w/env variable or similar
  logWorkflow.info('Waiting for withdrawal delay...')

  // stake removed from pendingWithdrawals after withdrawal timeout (21 days on MainNet, 3 min here)
  await withRetry(
    async () => {
      const updatedStakeResponse = await getAccountDelegatedStakes(
        urls,
        account.address,
      )
      return assertDelegatedStakes(updatedStakeResponse, [], [])
    },
    {
      name: 'assertDelegatedStakeRemovedFromState',
      maxAttempts: 36,
      interval: 1000 * 10,
      handleError: () => {},
    },
  )
  logWorkflow.info('Stake removed from pendingWithdrawal')

  await assertBalanceChange(account, initialBalance + originalStake.totalBalance)
  logWorkflow.info('Wallet balance updated')

  let ordinal
  await withRetry(
    async () => {
      const snapshot = await fetchSnapshot(urls, ordinal ? ordinal : 'latest')

      ordinal = snapshot.value.ordinal

      await assertRewardTxnInSnapshot(
        snapshot,
        account,
        originalStake.rewardAmount,
      )
      await assertTokenUnlockInSnapshot(
        snapshot,
        account,
        originalStake.tokenLockRef,
        originalStake.amount,
      )
    },
    {
      maxAttempts: 5,
      interval: 1,
      handleError: () => {
        // iterate backwards from latest to find snapshot w/rewards
        ordinal--
      },
    },
  )

  logWorkflow.info('Reward and TokenUnlock transactions sent')

  logWorkflow.info('---- End checkWithdrawDelegatedStake ----')
}

const testDelegatedStaking = async (urls) => {
  await checkCreateNodeParameters(urls)

  const account = setupDag4Account(urls)
  account.loginPrivateKey(PRIVATE_KEYS.key4)

  const nodeParams = await getNodeParams(urls)

  const stakeHash = await checkCreateDelegatedStake(
    urls,
    account,
    nodeParams[0].peerId,
  )

  const updatedStakeHash = await checkUpdateDelegatedStake(
    urls,
    account,
    stakeHash,
    nodeParams[1].peerId,
  )

  await checkWithdrawDelegatedStake(urls, account, updatedStakeHash)
}

const executeWorkflowByType = async (workflowType) => {
  const config = createConfig()
  const urls = createNetworkConfig(config)

  switch (workflowType) {
    case 'testDelegatedStaking':
      await testDelegatedStaking(urls)
      break
    default:
      throw new Error(`Unknown workflow type: ${workflowType}`)
  }
}

const workflowType = process.argv[4]
if (!workflowType) {
  logWorkflow.error('workflowType arg not found.')
  throwUsage()
}

executeWorkflowByType(workflowType).catch((err) => {
  console.log('err:')
  console.log(err)
  logWorkflow.error('-', err)
  throw err
})
