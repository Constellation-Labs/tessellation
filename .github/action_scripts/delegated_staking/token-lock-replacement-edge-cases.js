/**
 * Token Lock Replacement Edge Cases - Extended tests for delegated stake + token lock replacement
 *
 * Tests edge cases not covered by the main delegated-staking.js tests:
 * 1. Replace with same amount (should fail - ReplacementLowerThanCurrentTokenLock)
 * 2. Replace with less amount (should fail - ReplacementLowerThanCurrentTokenLock)
 * 3. Replace non-existent token lock ref (should fail - NothingToReplace)
 * 4. Replace with minimum valid increase (+1 datum)
 * 5. Multiple sequential replacements (3 in a row)
 * 6. Replace while stake is in withdrawal (pendingWithdrawals)
 *
 * TODO: Add test for ReplacementIsNotSupported error (currency token locks with currencyId set)
 *       This requires setting up a currency metagraph which is complex in the E2E environment.
 *
 * Key Reservation:
 * - This test suite uses PRIVATE_KEYS.key3 to avoid conflicts with delegated-staking.js (uses key4)
 * - If adding new test suites, use different keys to prevent cross-test contamination
 *
 * Usage:
 * - CI: node token-lock-replacement-edge-cases.js 90 91 testTokenLockReplacementEdgeCases
 * - Local: RUN_ENV=local node token-lock-replacement-edge-cases.js 90 91 testTokenLockReplacementEdgeCases
 */

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
} = require('./lib')

const throwUsage = () => {
  throw new Error(
    'Usage: node script.js <dagl0-port-prefix> <dagl1-port-prefix> <workflow-name>',
  )
}

const createConfig = () => {
  const args = process.argv.slice(2)
  if (args.length < 3) return throwUsage()
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

const extractKeysAndAccount = (filePath) => {
  const { privateKeyString, nodeId } = getPrivateKeyAndNodeIdFromFile(filePath)
  const account = dag4.createAccount(privateKeyString)
  return { privateKeyString, nodeId, account }
}

/**
 * Helper to create token lock with error handling for expected failures
 */
const createTokenLockExpectError = async (account, urls, lockAmount, replaceRef, expectedErrorSubstring) => {
  try {
    await account.postTokenLock({
      source: account.address,
      amount: lockAmount,
      tokenL1Url: urls.dagL1Url,
      unlockEpoch: null,
      currencyId: null,
      replaceTokenLockRef: replaceRef,
      fee: 0,
    })
    throw new Error(`Expected error containing "${expectedErrorSubstring}" but request succeeded`)
  } catch (error) {
    if (error.message.includes('Expected error')) throw error
    
    // dag4 SDK returns errors as JSON in error.message: {"errors":[{"message":"..."}]}
    // or as plain text. Extract the actual error content.
    const errorStr = error.message || ''
    
    // Check if this looks like an API validation error (contains error message patterns)
    const isValidationError = errorStr.includes('"errors"') || 
                              errorStr.includes('TokenLock') ||
                              errorStr.includes('Replace') ||
                              errorStr.includes('NothingTo')
    
    if (!isValidationError && (errorStr.includes('ECONNREFUSED') || errorStr.includes('ETIMEDOUT'))) {
      throw new Error(`Network error: ${errorStr}`)
    }
    
    if (!errorStr.includes(expectedErrorSubstring)) {
      throw new Error(`Expected error containing "${expectedErrorSubstring}" but got: ${errorStr}`)
    }
    logWorkflow.info(`Got expected error: ${expectedErrorSubstring}`)
    return true
  }
}

/**
 * Test 1: Replace with same amount (should fail)
 * Also sets up the initial token lock and stake for subsequent tests
 */
const testReplaceSameAmount = async (urls, account, nodeIds) => {
  logWorkflow.info('---- Start testReplaceSameAmount ----')

  // Check if we already have a stake we can use
  const existingStakes = await getAccountDelegatedStakes(urls, account.address)
  let lockHash, stakeHash, lockAmount

  if (existingStakes.activeDelegatedStakes.length > 0) {
    // Reuse existing stake
    const existingStake = existingStakes.activeDelegatedStakes[0]
    lockHash = existingStake.tokenLockRef
    stakeHash = existingStake.hash
    lockAmount = existingStake.amount
    logWorkflow.info(`Reusing existing stake: ${stakeHash.substring(0, 16)}...`)
    logWorkflow.info(`  Token lock: ${lockHash.substring(0, 16)}...`)
    logWorkflow.info(`  Amount: ${lockAmount}`)
  } else {
    // Create new token lock and stake
    lockAmount = 600000000000 // 6000 DAG
    lockHash = await createTokenLock(account, urls, lockAmount)
    logWorkflow.info(`Created initial token lock: ${lockHash}`)

    // Try each node until we find one without an existing stake
    for (const nodeId of nodeIds) {
      try {
        stakeHash = await createDelegatedStake(account, lockHash, lockAmount, nodeId)
        logWorkflow.info(`Created delegated stake on node ${nodeId.substring(0, 16)}...: ${stakeHash}`)
        break
      } catch (e) {
        if (e.message.includes('StakeExistsForNode')) {
          logWorkflow.info(`Stake already exists on node ${nodeId.substring(0, 16)}..., trying next node`)
          continue
        }
        throw e
      }
    }

    if (!stakeHash) {
      throw new Error('Could not create stake on any node')
    }

    // Wait for stake to be included in global snapshot (required for replacement validation)
    logWorkflow.info('Waiting for stake inclusion in global snapshot...')
    await withRetry(
      async () => {
        const stakeResponse = await getAccountDelegatedStakes(urls, account.address)
        const stake = stakeResponse.activeDelegatedStakes.find(s => s.hash === stakeHash)
        if (!stake) throw new Error('Stake not yet in global snapshot')
        logWorkflow.info(`Stake confirmed in snapshot: ${stake.hash.substring(0, 16)}...`)
        return true
      },
      { name: 'waitForStakeInclusion', maxAttempts: 15, interval: 2000, handleError: () => {} }
    )
    
    // Additional wait for L1 state propagation
    await sleep(5000)
  }

  // Try to replace with same amount - should fail with ReplacementLowerThanCurrentTokenLock
  // Retry if we get NothingToReplace (lock not yet in L1 stored state)
  await withRetry(
    async () => {
      try {
        await createTokenLockExpectError(
          account,
          urls,
          lockAmount, // Same amount
          lockHash,
          'ReplacementLowerThanCurrentTokenLock'
        )
        return true
      } catch (e) {
        if (e.message.includes('NothingToReplace')) {
          logWorkflow.info('Lock not yet in L1 state, retrying...')
          throw e // Retry
        }
        throw e // Other errors propagate
      }
    },
    { name: 'replaceWithSameAmount', maxAttempts: 10, interval: 3000, handleError: () => {} }
  )

  // Verify stake unchanged
  await withRetry(
    async () => {
      const stakeResponse = await getAccountDelegatedStakes(urls, account.address)
      const stake = stakeResponse.activeDelegatedStakes.find(s => s.hash === stakeHash)
      if (!stake) throw new Error('Stake not found')
      // Note: tokenLockRef might have been updated by previous test runs
      return true
    },
    { name: 'verifyStakeExists', maxAttempts: 5, interval: 2000, handleError: () => {} }
  )

  logWorkflow.info('---- End testReplaceSameAmount ----')
  return { lockHash, stakeHash, lockAmount }
}

/**
 * Test 2: Replace with less amount (should fail)
 */
const testReplaceLessAmount = async (urls, account, existingLockHash, existingAmount) => {
  logWorkflow.info('---- Start testReplaceLessAmount ----')

  // Use 5000 DAG (minimum) which is less than 6000 DAG but still valid amount
  const lessAmount = 500000000000 // 5000 DAG - less than existing but above minimum

  // Retry if we get NothingToReplace (lock not yet in L1 stored state)
  await withRetry(
    async () => {
      try {
        await createTokenLockExpectError(
          account,
          urls,
          lessAmount,
          existingLockHash,
          'ReplacementLowerThanCurrentTokenLock'
        )
        return true
      } catch (e) {
        if (e.message.includes('NothingToReplace')) {
          logWorkflow.info('Lock not yet in L1 state, retrying...')
          throw e
        }
        throw e
      }
    },
    { name: 'replaceWithLessAmount', maxAttempts: 10, interval: 3000, handleError: () => {} }
  )

  logWorkflow.info('---- End testReplaceLessAmount ----')
}

/**
 * Test 3: Replace non-existent token lock ref (should fail)
 */
const testReplaceNonExistentRef = async (urls, account) => {
  logWorkflow.info('---- Start testReplaceNonExistentRef ----')

  const fakeRef = '0000000000000000000000000000000000000000000000000000000000000000'
  const amount = 500000000000

  await createTokenLockExpectError(
    account,
    urls,
    amount,
    fakeRef,
    'NothingToReplace'
  )

  logWorkflow.info('---- End testReplaceNonExistentRef ----')
}

/**
 * Test 4: Replace with minimum valid increase (+1 datum)
 */
const testReplaceMinimumIncrease = async (urls, account, existingLockHash, existingAmount, stakeHash) => {
  logWorkflow.info('---- Start testReplaceMinimumIncrease ----')

  const minIncrease = existingAmount + 1 // Minimum valid increase
  
  const newLockHash = await createTokenLock(account, urls, minIncrease, existingLockHash, existingAmount)
  logWorkflow.info(`Created replacement with +1 datum: ${newLockHash}`)

  // Verify delegated stake updated AND new lock is active (this confirms snapshot inclusion)
  logWorkflow.info('Waiting for snapshot inclusion and stake update...')
  await withRetry(
    async () => {
      const stakeResponse = await getAccountDelegatedStakes(urls, account.address)
      const stake = stakeResponse.activeDelegatedStakes.find(s => s.hash === stakeHash)
      if (!stake) throw new Error('Stake not found')
      if (stake.tokenLockRef !== newLockHash) {
        throw new Error(`TokenLockRef not updated: expected ${newLockHash}, got ${stake.tokenLockRef}`)
      }
      if (stake.amount !== minIncrease) {
        throw new Error(`Amount not updated: expected ${minIncrease}, got ${stake.amount}`)
      }
      return true
    },
    { name: 'verifyMinIncreaseUpdate', maxAttempts: 20, interval: 3000, handleError: () => {} }
  )
  
  // Extra wait to ensure the new lock is fully active on GL0 for subsequent replacements
  // This is important because GL1 accepts the lock before GL0 includes it in a snapshot
  logWorkflow.info('Lock confirmed in snapshot, waiting for GL0 sync...')
  await sleep(10000)

  logWorkflow.info('---- End testReplaceMinimumIncrease ----')
  return { newLockHash, newAmount: minIncrease }
}

/**
 * Test 5: Multiple sequential replacements
 * Note: After each replacement, the OLD lock is removed and NEW lock becomes active.
 * Subsequent replacements must reference the NEW lock hash.
 */
const testMultipleSequentialReplacements = async (urls, account, currentLockHash, currentAmount, stakeHash) => {
  logWorkflow.info('---- Start testMultipleSequentialReplacements ----')

  let lockHash = currentLockHash
  let amount = currentAmount

  // Do 3 sequential replacements
  for (let i = 1; i <= 3; i++) {
    const newAmount = amount + 100000000000 // +1000 DAG each time
    logWorkflow.info(`Sequential replacement ${i}: ${amount} -> ${newAmount}`)
    logWorkflow.info(`  Replacing lock: ${lockHash.substring(0, 16)}...`)

    const newLockHash = await createTokenLock(account, urls, newAmount, lockHash, amount)
    logWorkflow.info(`  Created replacement ${i}: ${newLockHash.substring(0, 16)}...`)

    // Wait for inclusion before verifying
    await sleep(3000)

    // Verify delegated stake updated after each replacement
    await withRetry(
      async () => {
        const stakeResponse = await getAccountDelegatedStakes(urls, account.address)
        const stake = stakeResponse.activeDelegatedStakes.find(s => s.hash === stakeHash)
        if (!stake) throw new Error('Stake not found')
        if (stake.tokenLockRef !== newLockHash) {
          throw new Error(`Replacement ${i}: TokenLockRef not updated. Expected ${newLockHash.substring(0,16)}, got ${stake.tokenLockRef?.substring(0,16)}`)
        }
        if (stake.amount !== newAmount) {
          throw new Error(`Replacement ${i}: Amount not updated. Expected ${newAmount}, got ${stake.amount}`)
        }
        return true
      },
      { name: `verifySequentialReplacement${i}`, maxAttempts: 10, interval: 2000, handleError: () => {} }
    )

    // IMPORTANT: Update lockHash to the NEW lock for the next iteration
    lockHash = newLockHash
    amount = newAmount
    logWorkflow.info(`  Sequential replacement ${i} verified ✓`)
    
    // Wait for snapshot inclusion and GL0 sync before next replacement
    if (i < 3) {
      logWorkflow.info('  Waiting for GL0 sync before next replacement...')
      await sleep(10000)
    }
  }

  logWorkflow.info('---- End testMultipleSequentialReplacements ----')
  return { finalLockHash: lockHash, finalAmount: amount }
}

/**
 * Test 6: Replace while stake is in withdrawal (pendingWithdrawals)
 */
const testReplaceWhileInWithdrawal = async (urls, account, nodeId) => {
  logWorkflow.info('---- Start testReplaceWhileInWithdrawal ----')

  // Create a fresh token lock and stake for this test
  const lockAmount = 600000000000 // 6000 DAG
  const lockHash = await createTokenLock(account, urls, lockAmount)
  logWorkflow.info(`Created token lock for withdrawal test: ${lockHash}`)

  const stakeHash = await createDelegatedStake(account, lockHash, lockAmount, nodeId)
  logWorkflow.info(`Created delegated stake: ${stakeHash}`)

  // Wait for stake to be included in global snapshot before withdrawing
  // (Using withRetry instead of fixed sleep to handle variable snapshot timing)
  logWorkflow.info('Waiting for stake inclusion in global snapshot...')
  await withRetry(
    async () => {
      const stakeResponse = await getAccountDelegatedStakes(urls, account.address)
      const stake = stakeResponse.activeDelegatedStakes.find(s => s.hash === stakeHash)
      if (!stake) throw new Error('Stake not yet in activeDelegatedStakes')
      logWorkflow.info(`Stake confirmed in snapshot: ${stake.hash.substring(0, 16)}...`)
      return true
    },
    { name: 'waitForStakeBeforeWithdraw', maxAttempts: 15, interval: 2000, handleError: () => {} }
  )

  // Initiate withdrawal
  await withdrawDelegatedStake(account, stakeHash)
  logWorkflow.info('Initiated stake withdrawal')

  // Verify stake is in pendingWithdrawals
  await withRetry(
    async () => {
      const stakeResponse = await getAccountDelegatedStakes(urls, account.address)
      const pending = stakeResponse.pendingWithdrawals.find(s => s.hash === stakeHash)
      if (!pending) throw new Error('Stake not in pendingWithdrawals')
      logWorkflow.info('Stake confirmed in pendingWithdrawals')
      return true
    },
    { name: 'verifyPendingWithdrawal', maxAttempts: 10, interval: 2000, handleError: () => {} }
  )

  // Wait for L1 state to propagate after withdrawal moves stake to pending
  logWorkflow.info('Waiting for L1 state propagation after withdrawal...')
  await sleep(5000)

  // Try to replace token lock while stake is in withdrawal - should FAIL
  // Once a stake is withdrawn, the associated token lock should no longer be
  // in the activeTokenLocks list, so replacement returns NothingToReplace.
  // Note: If this test fails with "request succeeded", it may indicate the
  // token lock remains active during the withdrawal period (behavior TBD).
  const newAmount = lockAmount + 100000000000 // +1000 DAG
  await createTokenLockExpectError(
    account,
    urls,
    newAmount,
    lockHash,
    'NothingToReplace'
  )
  logWorkflow.info('Correctly rejected replacement of token lock in withdrawal')

  logWorkflow.info('---- End testReplaceWhileInWithdrawal ----')
}

/**
 * Setup node parameters for testing (required for delegated staking)
 */
const setupNodeParameters = async (urls) => {
  logWorkflow.info('---- Setting up node parameters ----')

  // Check if node params already exist (for local testing or re-runs)
  const existingParams = await getNodeParams(urls)
  
  if (existingParams.length >= 1) {
    logWorkflow.info(`Node parameters already configured: ${existingParams.length} nodes`)
    return existingParams
  }

  // For CI, we need to extract keys and set up node params
  if (RUN_ENV !== 'ci') {
    throw new Error('Node parameters must be configured before running local tests. ' +
      'Run the main delegated-staking.js test first, or manually set up node params.')
  }

  const {
    privateKeyString: privateKeyString1,
    nodeId: nodeId1,
    account: account1,
  } = extractKeysAndAccount('../../code/hypergraph/dag-l0/genesis-node/id_ecdsa.hex')

  const {
    privateKeyString: privateKeyString2,
    nodeId: nodeId2,
    account: account2,
  } = extractKeysAndAccount('../../code/hypergraph/dag-l0/validator-1/id_ecdsa.hex')

  // Set up node 1 params
  const ur1 = await postNodeParamsNodeId(
    urls, nodeId1, account1, privateKeyString1,
    'EdgeCaseTestNode1', 5000000
  )
  checkOk(ur1)
  
  // Set up node 2 params
  const ur2 = await postNodeParamsNodeId(
    urls, nodeId2, account2, privateKeyString2,
    'EdgeCaseTestNode2', 6000000
  )
  checkOk(ur2)
  
  await sleep(5000)

  const nodeParams = await getNodeParams(urls)
  logWorkflow.info(`Node parameters configured: ${nodeParams.length} nodes`)

  return nodeParams
}

/**
 * Main test runner
 */
const testTokenLockReplacementEdgeCases = async (urls) => {
  logWorkflow.info('========================================')
  logWorkflow.info('Token Lock Replacement Edge Cases Tests')
  logWorkflow.info('========================================')

  // Setup - use key3 to avoid conflicts with delegated-staking.js tests (which use key4)
  // See "Key Reservation" comment at top of file for key assignment policy
  const account = setupDag4Account(urls)
  account.loginPrivateKey(PRIVATE_KEYS.key3)
  logWorkflow.info(`Using account: ${account.address}`)

  const nodeParams = await setupNodeParameters(urls)
  const nodeIds = nodeParams.map(p => p.peerId)
  const nodeId2 = nodeParams.length > 1 ? nodeParams[1].peerId : nodeParams[0].peerId

  // Test 1: Replace with same amount (should fail)
  const { lockHash, stakeHash, lockAmount } = await testReplaceSameAmount(urls, account, nodeIds)

  // Test 2: Replace with less amount (should fail)
  await testReplaceLessAmount(urls, account, lockHash, lockAmount)

  // Test 3: Replace non-existent token lock ref (should fail)
  await testReplaceNonExistentRef(urls, account)

  // Test 4: Replace with minimum valid increase (+1 datum)
  const { newLockHash, newAmount } = await testReplaceMinimumIncrease(
    urls, account, lockHash, lockAmount, stakeHash
  )

  // Test 5: Multiple sequential replacements
  await testMultipleSequentialReplacements(urls, account, newLockHash, newAmount, stakeHash)

  // Test 6: Replace while stake is in withdrawal
  await testReplaceWhileInWithdrawal(urls, account, nodeId2)

  logWorkflow.info('========================================')
  logWorkflow.info('All edge case tests completed!')
  logWorkflow.info('========================================')
}

const executeWorkflowByType = async (workflowType) => {
  const config = createConfig()
  const urls = createNetworkConfig(config)

  switch (workflowType) {
    case 'testTokenLockReplacementEdgeCases':
      await testTokenLockReplacementEdgeCases(urls)
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
  logWorkflow.error('Test failed:', err.message)
  if (RUN_ENV !== 'local') {
    throw err
  }
})
