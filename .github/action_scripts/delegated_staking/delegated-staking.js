/**
 * Delegated Staking tests - run with CI or local (Euclid)
 *
 * Local run instructions:
 * - Start Euclid from genesis (`hydra start-genesis`)
 * - RUN_ENV=local node .github/action_scripts/delegated_staking/delegated-staking 90 91 testDelegatedStaking
 * - Reset Euclid to run again (`hydra stop && hydra start-genesis`)
 */

const path = require('path')
const axios = require('axios')
const { dag4 } = require('@stardust-collective/dag4')

const RUN_ENV = process.env.RUN_ENV || 'ci'

const {
  parseSharedArgs,
  PRIVATE_KEYS,
  sleep,
  withRetry,
  createNetworkConfig,
  logWorkflow,
} = require('../shared')

const {
  checkOk,
  checkBadRequest,
  dagToDatum,
  getPrivateKeyAndNodeIdFromFile,
  postNodeParamsNodeId,
  createDelegatedStake,
  withdrawDelegatedStake,
  getAccountDelegatedStakes,
  assertDelegatedStakes,
  fetchStakeWithRewardsBalance,
  createTokenLock,
  assertBalanceChange,
  getNodeParams,
  fetchSnapshot,
  assertRewardTxnInSnapshot,
  assertTokenUnlockInSnapshot,
} = require('./lib')

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

const setupDag4Account = (urls) => {
  dag4.account.connect({
    networkVersion: '2.0',
    l0Url: urls.globalL0Url,
    l1Url: urls.dagL1Url,
  })

  return dag4.account
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

const thirdNodeParameterName1 = 'ThirdNode1'
const thirdNodeFraction1 = 7500000

const testCreateNodeParameters = async (urls) => {
  logWorkflow.info('---- Start testCreateNodeParameters ----')
  const initialNodeParams = await getNodeParams(urls)
  verifyInitialNodeParams(initialNodeParams)
  logWorkflow.info('Initial node params is OK')

  const {
    privateKeyString: privateKeyString1,
    nodeId: nodeId1,
    account: account1,
  } = extractKeysAndAccount(
    RUN_ENV === 'ci'
      ? '../../code/hypergraph/dag-l0/genesis-node/id_ecdsa.hex'
      : path.join(__dirname, 'keys', 'genesis-node.hex'),
  )

  const {
    privateKeyString: privateKeyString2,
    nodeId: nodeId2,
    account: account2,
  } = extractKeysAndAccount(
    RUN_ENV === 'ci'
      ? '../../code/hypergraph/dag-l0/validator-1/id_ecdsa.hex'
      : path.join(__dirname, 'keys', 'validator-1-node.hex'),
  )

  const {
    privateKeyString: privateKeyString3,
    nodeId: nodeId3,
    account: account3,
  } = extractKeysAndAccount(
    RUN_ENV === 'ci'
      ? '../../code/hypergraph/dag-l0/validator-2/id_ecdsa.hex'
      : path.join(__dirname, 'keys', 'validator-2-node.hex'),
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
  logWorkflow.info('create node params 1 is OK')

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

  logWorkflow.info('Check updating node 2 with correct params')
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

  logWorkflow.info('Create third node params')
  const third = await postNodeParamsNodeId(
    urls,
    nodeId3,
    account3,
    privateKeyString3,
    thirdNodeParameterName1,
    thirdNodeFraction1,
  )
  checkOk(third)

  // tends to fail here in CI, wait a little longer
  await sleep(5000)

  const allNodeParams = await getNodeParams(urls)
  if (allNodeParams.length !== 3) {
    throw new Error(`Expected 3 node params, got ${allNodeParams.length}`)
  }

  verifyNodeParamsResponse(
    allNodeParams,
    nodeId1,
    firstNodeParameterName2,
    firstNodeFraction2,
  )
  verifyNodeParamsResponse(
    allNodeParams,
    nodeId2,
    secondNodeParameterName1,
    secondNodeFraction1,
  )
  verifyNodeParamsResponse(
    allNodeParams,
    nodeId3,
    thirdNodeParameterName1,
    thirdNodeFraction1,
  )
  logWorkflow.info('All nodes check is OK')

  logWorkflow.info('---- End testCreateNodeParameters ----')
}

const testCreateDelegatedStake = async (urls, account, nodeIds) => {
  logWorkflow.info('---- Start testCreateDelegatedStake ----')

  const lockAmount = 500000000000
  const lockHash = await createTokenLock(account, urls, lockAmount)

  const stakeResponse = await getAccountDelegatedStakes(urls, account.address)
  assertDelegatedStakes(stakeResponse, [], [])
  logWorkflow.info('Initial stakes are empty')

  const stakeHash = await createDelegatedStake(
    account,
    lockHash,
    lockAmount,
    nodeIds[0],
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
            nodeId: nodeIds[0],
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

  logWorkflow.info('Creating 2nd stake')
  const secondLockAmount = 1200012345678
  const secondLockHash = await createTokenLock(account, urls, secondLockAmount)

  const secondStakeHash = await createDelegatedStake(
    account,
    secondLockHash,
    secondLockAmount,
    nodeIds[1],
  )
  logWorkflow.info('Stake 2 created')

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
            nodeId: nodeIds[0],
            amount: lockAmount,
          },
          {
            hash: secondStakeHash,
            nodeId: nodeIds[1],
            amount: secondLockAmount,
          },
        ],
        [],
      )
    },
    {
      name: 'assertDelegatedStake2Created',
      maxAttempts: 10,
      interval: 1000,
      handleError: (err, attempt) => {
        if (attempt !== 10) return

        console.log(`assertDelegatedStake2Created failed: ${err.message}`)
        throw err
      },
    },
  )

  logWorkflow.info('Stake 2 creation verified')

  logWorkflow.info('---- End testCreateDelegatedStake ----')

  return [stakeHash, secondStakeHash]
}

const testUpdateDelegatedStake = async (urls, account, stakeHash, nodeId) => {
  logWorkflow.info('---- Start testUpdateDelegatedStake ----')

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

  // get other stake so we can verify it hasn't changed
  const stakeResponse = await getAccountDelegatedStakes(urls, account.address)
  const otherStake = stakeResponse.activeDelegatedStakes.find(
    (stake) => stake.hash !== stakeHash,
  )

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
          {
            hash: otherStake.hash,
            nodeId: otherStake.nodeId,
            amount: otherStake.amount,
            tokenLockRef: otherStake.tokenLockRef,
            rewardAmount: otherStake.rewardAmount,
          },
        ],
        [],
      )
    },
    {
      name: 'assertDelegatedStakeUpdated',
      maxAttempts: 10,
      interval: 1000,
      handleError: (err, attempt) => {
        if (attempt !== 10) return

        console.log(`assertDelegatedStakeUpdated failed: ${err.message}`)
        throw err
      },
    },
  )
  logWorkflow.info('Stake update verified with balance change')

  logWorkflow.info('---- End testUpdateDelegatedStake ----')

  return updatedStakeHash
}

const testWithdrawDelegatedStake = async (urls, account, stakeHash) => {
  logWorkflow.info('---- Start testWithdrawDelegatedStake ----')

  const initialBalance = dagToDatum(await account.getBalance())

  const originalStake = await fetchStakeWithRewardsBalance(
    urls,
    account.address,
    stakeHash,
  )

  if (!originalStake) {
    throw new Error('Stake not found, cannot test updating stake')
  }

  // get other stake so we can verify it hasn't changed
  const stakeResponse = await getAccountDelegatedStakes(urls, account.address)
  const otherStake = stakeResponse.activeDelegatedStakes.find(
    (stake) => stake.hash !== stakeHash,
  )

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
        [
          {
            hash: otherStake.hash,
            nodeId: otherStake.nodeId,
            amount: otherStake.amount,
            tokenLockRef: otherStake.tokenLockRef,
            rewardAmount: otherStake.rewardAmount,
          },
        ],
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
      handleError: (err, attempt) => {
        if (attempt !== 10) return

        console.log(`assertDelegatedStakeMovedToPending failed: ${err.message}`)
        throw err
      },
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
      return assertDelegatedStakes(
        updatedStakeResponse,
        [
          {
            hash: otherStake.hash,
            nodeId: otherStake.nodeId,
            amount: otherStake.amount,
            tokenLockRef: otherStake.tokenLockRef,
          },
        ],
        [],
      )
    },
    {
      name: 'assertDelegatedStakeRemovedFromState',
      maxAttempts: 36,
      interval: 1000 * 10,
      handleError: (err, attempt) => {
        if (attempt !== 36) return

        console.log(`assertDelegatedStakeRemovedFromState failed: ${err.message}`)
        throw err
      },
    },
  )
  logWorkflow.info('Stake removed from pendingWithdrawal')

  await assertBalanceChange(
    account,
    initialBalance + originalStake.totalBalance,
  )
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

  logWorkflow.info('---- End testWithdrawDelegatedStake ----')
}

const testDelegatedStaking = async (urls) => {
  const account = setupDag4Account(urls)
  account.loginPrivateKey(PRIVATE_KEYS.key4)
  
  await testCreateNodeParameters(urls)

  const nodeParams = await getNodeParams(urls)

  const [stakeHash] = await testCreateDelegatedStake(urls, account, [
    nodeParams[0].peerId,
    nodeParams[1].peerId,
  ])

  const updatedStakeHash = await testUpdateDelegatedStake(
    urls,
    account,
    stakeHash,
    nodeParams[2].peerId,
  )

  await testWithdrawDelegatedStake(urls, account, updatedStakeHash)
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
  if (RUN_ENV !== 'local') {
    throw err
  }
})
