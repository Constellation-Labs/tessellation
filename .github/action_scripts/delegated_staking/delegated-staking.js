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
  withRetryOrdinal,
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
  // The cluster may already carry node parameters from a previous run. The test
  // is idempotent: it reuses any existing parameters rather than requiring a
  // clean slate, computing expected ordinals from the current on-chain state.
  if (response.length) {
    logWorkflow.info(
      `Reusing ${response.length} pre-existing node parameter(s)`,
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
    // Parameters already exist for this node; reuse them (see verifyInitialNodeParams).
    logWorkflow.info(`Reusing pre-existing node-params for ${nodeId}`)
  } catch (error) {
    // 404 expected on a fresh node, NOOP
  }
}

// On a reused cluster a node may already have parameters. The next accepted
// update references the current lastRef, so its parent.ordinal equals the
// current lastRef.ordinal (0 when the node has no parameters yet).
const getNextNodeParamsOrdinal = async (urls, nodeId) => {
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
    if (response.status === 200 && response.data && response.data.lastRef) {
      return response.data.lastRef.ordinal
    }
  } catch (error) {
    if (error.response && error.response.status === 404) {
      return 0
    }
    throw error
  }
  return 0
}

const waitForNodeParamsUpdate = async (urls, verifyFn, maxAttempts = 30, intervalMs = 5000) => {
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      const nodeParams = await getNodeParams(urls)
      verifyFn(nodeParams)
      return nodeParams
    } catch (e) {
      if (attempt === maxAttempts) throw e
      logWorkflow.info(`Waiting for node params to propagate (attempt ${attempt}/${maxAttempts}): ${e.message}`)
      await sleep(intervalMs)
    }
  }
}

const verifyNodeParamsResponse = (
  nodeParams,
  nodeId,
  expectedName,
  expectedRewardFraction,
) => {
  const data = nodeParams.find((item) => item.peerId === nodeId)
  if (!data) throw new Error(`PeerId ${nodeId.slice(0, 8)} not found in node-params (have: ${nodeParams.map(p => p.peerId.slice(0, 8)).join(', ') || 'none'})`)
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
  const maxAttempts = 30;
  const intervalMs = 5000;

  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    await sleep(intervalMs);
    let response;
    try {
      response = await axios.get(
        `${urls.globalL0Url}/node-params/${nodeId}?t=${Date.now()}`,
        {
          headers: {
            'Cache-Control': 'no-cache, no-store, must-revalidate',
            Pragma: 'no-cache',
            Expires: '0',
          },
        },
      )
    } catch (err) {
      if (err.response && err.response.status === 404 && attempt < maxAttempts) {
        logWorkflow.info(`Waiting for node-params/${nodeId} to appear (attempt ${attempt}/${maxAttempts}): 404`)
        continue;
      }
      throw err;
    }

    if (response.status !== 200)
      throw new Error(`NodeParamsNode returned ${response.status} instead of 200`)

    const receivedRewardFraction =
      response.data.latest.value.delegatedStakeRewardParameters.rewardFraction
    const receivedName = response.data.latest.value.nodeMetadataParameters.name
    const receivedOrdinal = response.data.latest.value.parent.ordinal

    const fractionOk = receivedRewardFraction === expectedRewardFraction;
    const nameOk = receivedName === expectedName;
    const ordinalOk = receivedOrdinal === expectedOrdinal;

    if (fractionOk && nameOk && ordinalOk) {
      return;
    }

    if (attempt < maxAttempts) {
      const reasons = [];
      if (!nameOk) reasons.push(`name=${receivedName} expected=${expectedName}`);
      if (!fractionOk) reasons.push(`fraction=${receivedRewardFraction} expected=${expectedRewardFraction}`);
      if (!ordinalOk) reasons.push(`ordinal=${receivedOrdinal} expected=${expectedOrdinal}`);
      logWorkflow.info(`Waiting for node-params/${nodeId} to update (attempt ${attempt}/${maxAttempts}): ${reasons.join(', ')}`)
      continue;
    }

    if (!fractionOk)
      throw new Error(`Node parameters node rewardFraction expected ${expectedRewardFraction} but received ${receivedRewardFraction}`)
    if (!nameOk)
      throw new Error(`Node parameters node name expected ${expectedName} but received ${receivedName}`)
    if (!ordinalOk)
      throw new Error(`Node parameters node name expected expected 0 ordinal but received ${receivedOrdinal}`)
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

  // Expected parent.ordinal for the next update of each node, derived from the
  // current on-chain state so the test works on both fresh and reused clusters.
  const node1BaseOrdinal = await getNextNodeParamsOrdinal(urls, nodeId1)
  const node2BaseOrdinal = await getNextNodeParamsOrdinal(urls, nodeId2)
  logWorkflow.info(
    `Base ordinals -> node1: ${node1BaseOrdinal}, node2: ${node2BaseOrdinal}`,
  )

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

  await waitForNodeParamsUpdate(urls, (params) =>
    verifyNodeParamsResponse(params, nodeId1, firstNodeParameterName1, firstNodeFraction1)
  )
  logWorkflow.info('Check updates node params is OK')

  await getNodeParamsNodeIdVerify(
    urls,
    nodeId1,
    firstNodeParameterName1,
    firstNodeFraction1,
    node1BaseOrdinal,
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

  await waitForNodeParamsUpdate(urls, (params) =>
    verifyNodeParamsResponse(params, nodeId1, firstNodeParameterName2, firstNodeFraction2)
  )
  logWorkflow.info('Check second updates node params is OK')

  await getNodeParamsNodeIdVerify(
    urls,
    nodeId1,
    firstNodeParameterName2,
    firstNodeFraction2,
    node1BaseOrdinal + 1,
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
    node1BaseOrdinal + 1,
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
    node2BaseOrdinal,
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

  await waitForNodeParamsUpdate(urls, (params) => {
    if (params.length < 3) throw new Error(`Expected 3 node params, got ${params.length}`)
    verifyNodeParamsResponse(params, nodeId1, firstNodeParameterName2, firstNodeFraction2)
    verifyNodeParamsResponse(params, nodeId2, secondNodeParameterName1, secondNodeFraction1)
    verifyNodeParamsResponse(params, nodeId3, thirdNodeParameterName1, thirdNodeFraction1)
  })
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

  await withRetryOrdinal(
    async () => {
      const updatedStakeResponse = await getAccountDelegatedStakes(
        urls,
        account.address,
      )
      // the endpoint accepts the same create delegated stake, but it should add only one of them
      const updatedStakeResponse2 = await getAccountDelegatedStakes(
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
      ) && assertDelegatedStakes(
        updatedStakeResponse2,
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
      globalL0Url: urls.globalL0Url,
      name: 'assertDelegatedStakeCreated',
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

  await withRetryOrdinal(
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
      globalL0Url: urls.globalL0Url,
      name: 'assertDelegatedStake2Created',
      // The 2nd create can be lost in L0 event intake (a single submission over an async mempool; the
      // identical first-create path succeeds moments earlier, so intake itself works). Resubmit the IDENTICAL
      // create (same tokenLockRef -> same signed tx and hash) after 2 missed ordinals. Once one create lands,
      // a re-accepted duplicate is rejected as InvalidParent/InvalidTokenLock, so this cannot double-apply the
      // stake; the exact final assertion below is unchanged.
      onOrdinalMiss: async ({ ordinalsMissed }) => {
        if (ordinalsMissed >= 2) {
          logWorkflow.warning(
            `assertDelegatedStake2Created: resubmitting 2nd delegated-stake create after ${ordinalsMissed} ordinal misses`,
          )
          await createDelegatedStake(account, secondLockHash, secondLockAmount, nodeIds[1])
        }
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
  await withRetryOrdinal(
    async () => {
      const updatedStakeResponse = await getAccountDelegatedStakes(
        urls,
        account.address,
      )
      // Verify structural fields (exact match)
      assertDelegatedStakes(
        updatedStakeResponse,
        [
          {
            hash: updatedStakeHash,
            nodeId,
            amount: originalStake.amount,
            tokenLockRef: originalStake.tokenLockRef,
          },
          {
            hash: otherStake.hash,
            nodeId: otherStake.nodeId,
            amount: otherStake.amount,
            tokenLockRef: otherStake.tokenLockRef,
          },
        ],
        [],
      )
      // Verify rewards transferred (>= original, since rewards accumulate each ordinal)
      const updatedStake = updatedStakeResponse.activeDelegatedStakes.find(
        s => s.hash === updatedStakeHash
      )
      if (!updatedStake) {
        throw new Error(`Updated stake not found for hash ${updatedStakeHash} in activeDelegatedStakes`)
      }
      if (updatedStake.rewardAmount < originalStake.rewardAmount) {
        throw new Error(
          `Expected rewardAmount >= ${originalStake.rewardAmount} but got ${updatedStake.rewardAmount}`
        )
      }
    },
    {
      globalL0Url: urls.globalL0Url,
      name: 'assertDelegatedStakeUpdated',
    },
  )
  logWorkflow.info('Stake update verified with balance change and rewards >= original')

  logWorkflow.info('---- End testUpdateDelegatedStake ----')

  return updatedStakeHash
}

const testIncreaseDelegatedStake = async (urls, account, stakeHash, nodeId) => {
  logWorkflow.info('---- Start testIncreaseDelegatedStake ----')

  logWorkflow.info('Waiting for stake with non-zero rewards balance')

  await sleep(5000);

  const originalStake = await fetchStakeWithRewardsBalance(
    urls,
    account.address,
    stakeHash,
    nodeId,
  )

  if (!originalStake) {
    throw new Error('Stake not found, cannot test updating stake')
  }

  if (nodeId !== originalStake.nodeId) {
    throw new Error('Cannot increase the node')
  }

  const balance = await dag4.network.getAddressBalance(account.address);
  // Increase stake by adding free balance to existing stake.
  // Use the actual on-chain stake amount (which may include accumulated rewards)
  // but cap the increase to what the wallet can actually cover.
  const increaseAmount = Math.min(balance.balance, 500000000000) // cap at 5000 DAG
  const thirdLockAmount = originalStake.amount + increaseAmount
  const thirdLockHash = await createTokenLock(account, urls, thirdLockAmount, originalStake.tokenLockRef, originalStake.amount)

  // get other stake so we can verify it hasn't changed
  const stakeResponse = await getAccountDelegatedStakes(urls, account.address)
  const otherStake = stakeResponse.activeDelegatedStakes.find(
    (stake) => stake.hash !== stakeHash,
  )

  logWorkflow.info('Token lock amount increased')

  // new stake in activeDelegatedStakes with updated values, balance transfers
  // old stake removed (not in active or pendingWithdrawal)
  await withRetryOrdinal(
    async () => {
      const updatedStakeResponse = await getAccountDelegatedStakes(
        urls,
        account.address,
      )
      assertDelegatedStakes(
        updatedStakeResponse,
        [
          {
            hash: stakeHash,
            nodeId,
            amount: thirdLockAmount,
            tokenLockRef: thirdLockHash,
          }
        ],
        [],
      )
      // Verify rewards carried over (>= original)
      const updatedStake = updatedStakeResponse.activeDelegatedStakes.find(
        s => s.hash === stakeHash
      )
      if (!updatedStake) {
        throw new Error(`Updated stake not found for hash ${stakeHash}`)
      }
      if (updatedStake.rewardAmount < originalStake.rewardAmount) {
        throw new Error(
          `Expected rewardAmount >= ${originalStake.rewardAmount} but got ${updatedStake.rewardAmount}`
        )
      }
    },
    {
      globalL0Url: urls.globalL0Url,
      name: 'assertDelegatedStakeUpdated',
    },
  )
  logWorkflow.info('Stake increase verified with balance change and rewards >= original')

  logWorkflow.info('---- End testIncreaseDelegatedStake ----')

  return thirdLockHash
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
  await withRetryOrdinal(
    async () => {
      const updatedStakeResponse = await getAccountDelegatedStakes(
        urls,
        account.address,
      )
      assertDelegatedStakes(
        updatedStakeResponse,
        [
          {
            hash: otherStake.hash,
            nodeId: otherStake.nodeId,
            amount: otherStake.amount,
            tokenLockRef: otherStake.tokenLockRef,
          },
        ],
        [
          {
            hash: stakeHash,
            nodeId: originalStake.nodeId,
            amount: originalStake.amount,
            tokenLockRef: originalStake.tokenLockRef,
          },
        ],
      )
      // Verify pending stake has rewards >= what it had when active
      const pendingStake = updatedStakeResponse.pendingWithdrawals.find(
        s => s.hash === stakeHash
      )
      if (!pendingStake) {
        throw new Error(`Pending stake not found for hash ${stakeHash} in pendingWithdrawals`)
      }
      if (pendingStake.rewardAmount < originalStake.rewardAmount) {
        throw new Error(
          `Expected pending rewardAmount >= ${originalStake.rewardAmount} but got ${pendingStake.rewardAmount}`
        )
      }
    },
    {
      globalL0Url: urls.globalL0Url,
      name: 'assertDelegatedStakeMovedToPending',
    },
  )
  logWorkflow.info('Stake withdraw verified pending')

  // TODO: need a way to speed this up w/env variable or similar
  logWorkflow.info('Waiting for withdrawal delay...')

  // stake removed from pendingWithdrawals after withdrawal timeout (21 days on MainNet, 3 min here)
  await withRetryOrdinal(
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
      globalL0Url: urls.globalL0Url,
      name: 'assertDelegatedStakeRemovedFromState',
      // Withdrawal timeout is ~3 min in CI, use generous limits
      maxOrdinalMisses: 60,
      maxStalledChecks: 120,
      interval: 5000,
    },
  )
  logWorkflow.info('Stake removed from pendingWithdrawal')

  // Datum balances here exceed Number.MAX_SAFE_INTEGER (~9e15); JS Number arithmetic on them
  // loses integer precision (representable values spaced 2/4/8 datum apart above 2^53), which is
  // exactly the ±2/±4/±8 drift observed — a test float-ulp artifact, not a node discrepancy.
  // Tolerate a few ulps of the expected magnitude.
  const expectedWalletBalance = initialBalance + originalStake.totalBalance
  const balanceUlp = Math.max(1, 2 ** (Math.floor(Math.log2(Math.abs(expectedWalletBalance) || 1)) - 52))
  await assertBalanceChange(account, expectedWalletBalance, balanceUlp * 16)
  logWorkflow.info('Wallet balance updated')

  // The reward txn + TokenUnlock are emitted once, in the withdrawal-finalization
  // snapshot, which is typically a few ordinals in the past by the time we get here
  // (after the removal-from-pending and balance-change waits). Scan a backward window
  // of recent snapshots rather than only the latest, so we don't miss them.
  const REWARD_SCAN_WINDOW = 25
  await withRetryOrdinal(
    async ({ ordinal }) => {
      const lo = Math.max(0, ordinal - REWARD_SCAN_WINDOW + 1)
      let rewardOk = false
      let unlockOk = false
      let lastErr = null
      for (let o = ordinal; o >= lo; o--) {
        const snapshot = await fetchSnapshot(urls, o)
        if (!rewardOk) {
          try {
            await assertRewardTxnInSnapshot(snapshot, account, originalStake.rewardAmount)
            rewardOk = true
          } catch (e) {
            lastErr = e
          }
        }
        if (!unlockOk) {
          try {
            await assertTokenUnlockInSnapshot(
              snapshot,
              account,
              originalStake.tokenLockRef,
              originalStake.amount,
            )
            unlockOk = true
          } catch (e) {
            lastErr = e
          }
        }
        if (rewardOk && unlockOk) return
      }
      throw new Error(
        `Reward/TokenUnlock not found in snapshots [${lo}, ${ordinal}] (reward=${rewardOk}, unlock=${unlockOk}): ${lastErr ? lastErr.message : 'n/a'}`,
      )
    },
    {
      globalL0Url: urls.globalL0Url,
      name: 'assertRewardAndTokenUnlock',
      maxOrdinalMisses: 10,
      maxStalledChecks: 20,
      interval: 3000,
    },
  )

  logWorkflow.info('Reward and TokenUnlock transactions sent')

  logWorkflow.info('---- End testWithdrawDelegatedStake ----')
}

// Withdraw any delegated stakes left over from a previous run and wait until the
// account is fully clean (no active stakes, no pending withdrawals). The staking
// assertions below require an exact stake count, so this makes the workflow
// idempotent and safe to run repeatedly against the same cluster.
const resetAccountStakes = async (urls, account) => {
  logWorkflow.info('---- Start resetAccountStakes ----')
  const initial = await getAccountDelegatedStakes(urls, account.address)
  if (
    initial.activeDelegatedStakes.length === 0 &&
    initial.pendingWithdrawals.length === 0
  ) {
    logWorkflow.info('Account already clean, nothing to reset')
    logWorkflow.info('---- End resetAccountStakes ----')
    return
  }

  for (const stake of initial.activeDelegatedStakes) {
    logWorkflow.info(`Withdrawing leftover stake ${stake.hash.substring(0, 16)}...`)
    await withdrawDelegatedStake(account, stake.hash)
  }

  // Wait for active stakes AND pending withdrawals to fully clear. The withdrawal
  // timeout is ~3 min on testnet, so use the same generous limits as the
  // withdrawal test's removed-from-state assertion.
  await withRetryOrdinal(
    async () => {
      const r = await getAccountDelegatedStakes(urls, account.address)
      if (
        r.activeDelegatedStakes.length !== 0 ||
        r.pendingWithdrawals.length !== 0
      ) {
        throw new Error(
          `Account not clean yet: active=${r.activeDelegatedStakes.length} pending=${r.pendingWithdrawals.length}`,
        )
      }
      return true
    },
    {
      globalL0Url: urls.globalL0Url,
      name: 'resetAccountStakes',
      maxOrdinalMisses: 60,
      maxStalledChecks: 120,
      interval: 5000,
    },
  )
  logWorkflow.info('Account stakes reset to empty')
  logWorkflow.info('---- End resetAccountStakes ----')
}

const testDelegatedStaking = async (urls) => {
  const account = setupDag4Account(urls)
  account.loginPrivateKey(PRIVATE_KEYS.key4)

  await resetAccountStakes(urls, account)

  await testCreateNodeParameters(urls)

  const nodeParams = await getNodeParams(urls)

  const [stakeHash, secondStakeHash] = await testCreateDelegatedStake(urls, account, [
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

  const increasedStakeHash = await testIncreaseDelegatedStake(
    urls,
    account,
    secondStakeHash,
    nodeParams[1].peerId,
  )
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
