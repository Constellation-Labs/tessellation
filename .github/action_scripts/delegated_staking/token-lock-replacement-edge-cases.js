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
 * - Local: RUN_ENV=local node .github/action_scripts/delegated_staking/token-lock-replacement-edge-cases.js 90 91 testTokenLockReplacementEdgeCases
 */

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
  resolveNodeKeyPath,
  postNodeParamsNodeId,
  createDelegatedStake,
  withdrawDelegatedStake,
  getAccountDelegatedStakes,
  assertDelegatedStakes,
  fetchStakeWithRewardsBalance,
  createTokenLock,
  assertBalanceChange,
  getNodeParams,
  waitForStakeInclusion,
  waitForStakeWithdrawal,
  waitForTokenLockInclusion,
  getActiveTokenLocks,
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
 * Create a REPLACEMENT token lock, tolerating the dag-L1's MPT trailing the global L0.
 *
 * A dag-L1 validates token-lock replacements against its own MPT, which it syncs from
 * global snapshots on a ~10s loop while GL0 finalizes ordinals ~20s apart, so the L1
 * steadily trails GL0 by ~1 ordinal. Immediately after a lock is confirmed in GL0 state,
 * posting its replacement can race ahead of the L1's MPT and be rejected at admission
 * with NothingToReplace. The parent lock IS in global state -- this is pure propagation
 * lag -- so retry across ordinal progressions until the L1 catches up. (testReplaceSameAmount
 * / testReplaceLessAmount already apply this on their expected-error path; the success
 * paths need the same tolerance.)
 */
const createReplacementTokenLock = (account, urls, lockAmount, replaceRef, replaceBalance) =>
  withRetryOrdinal(
    async () => {
      try {
        return await createTokenLock(account, urls, lockAmount, replaceRef, replaceBalance)
      } catch (e) {
        if (e.message && e.message.includes('NothingToReplace')) {
          logWorkflow.info('Replacement parent not yet in L1 MPT, waiting for ordinal progression...')
        }
        throw e
      }
    },
    {
      globalL0Url: urls.globalL0Url,
      name: 'createReplacementTokenLock',
      maxOrdinalMisses: 10,
      maxStalledChecks: 30,
    },
  )

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

    // Wait for stake to be included using ordinal-aware retry (detects dropped txs)
    logWorkflow.info('Waiting for stake inclusion in global snapshot...')
    const stake = await waitForStakeInclusion(urls, account.address, stakeHash)
    logWorkflow.info(`Stake confirmed in snapshot: ${stake.hash.substring(0, 16)}...`)
  }

  // Try to replace with same amount - should fail with ReplacementLowerThanCurrentTokenLock
  // Use ordinal-aware retry to handle L1 state propagation
  await withRetryOrdinal(
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
          logWorkflow.info('Lock not yet in L1 state, waiting for ordinal progression...')
          throw e // Retry after ordinal check
        }
        throw e // Other errors propagate
      }
    },
    { globalL0Url: urls.globalL0Url, name: 'replaceWithSameAmount', maxOrdinalMisses: 10 }
  )

  // Verify stake unchanged using ordinal-aware retry
  await withRetryOrdinal(
    async () => {
      const stakeResponse = await getAccountDelegatedStakes(urls, account.address)
      const stake = stakeResponse.activeDelegatedStakes.find(s => s.hash === stakeHash)
      if (!stake) throw new Error('Stake not found')
      return true
    },
    { globalL0Url: urls.globalL0Url, name: 'verifyStakeExists', maxOrdinalMisses: 6 }
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

  // Use ordinal-aware retry to handle L1 state propagation
  await withRetryOrdinal(
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
          logWorkflow.info('Lock not yet in L1 state, waiting for ordinal progression...')
          throw e
        }
        throw e
      }
    },
    { globalL0Url: urls.globalL0Url, name: 'replaceWithLessAmount', maxOrdinalMisses: 10 }
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
  
  const newLockHash = await createReplacementTokenLock(account, urls, minIncrease, existingLockHash, existingAmount)
  logWorkflow.info(`Created replacement with +1 datum: ${newLockHash}`)

  // Verify delegated stake updated using ordinal-aware retry
  logWorkflow.info('Waiting for snapshot inclusion and stake update...')
  await withRetryOrdinal(
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
    { globalL0Url: urls.globalL0Url, name: 'verifyMinIncreaseUpdate', maxOrdinalMisses: 10, maxStalledChecks: 30 }
  )
  
  // Brief wait for L1 sync after ordinal progression confirmed
  logWorkflow.info('Lock confirmed in snapshot, waiting for GL0 sync...')
  await sleep(5000)

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

    const newLockHash = await createReplacementTokenLock(account, urls, newAmount, lockHash, amount)
    logWorkflow.info(`  Created replacement ${i}: ${newLockHash.substring(0, 16)}...`)

    // Verify delegated stake updated using ordinal-aware retry
    await withRetryOrdinal(
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
      { globalL0Url: urls.globalL0Url, name: `verifySequentialReplacement${i}`, maxOrdinalMisses: 10 }
    )

    // IMPORTANT: Update lockHash to the NEW lock for the next iteration
    lockHash = newLockHash
    amount = newAmount
    logWorkflow.info(`  Sequential replacement ${i} verified ✓`)
    
    // Wait for ordinal progression + L1 sync before next replacement
    if (i < 3) {
      logWorkflow.info('  Waiting for ordinal progression before next replacement...')
      await withRetryOrdinal(
        async ({ ordinal, prevOrdinal }) => {
          if (!prevOrdinal) throw new Error('Waiting for first ordinal')
          if (ordinal - prevOrdinal < 1) throw new Error(`Waiting for ordinal progression: ${ordinal}`)
          return true
        },
        { globalL0Url: urls.globalL0Url, name: `waitBeforeReplacement${i + 1}`, maxOrdinalMisses: 10, maxStalledChecks: 30 }
      )
      await sleep(5000) // Extra buffer for L1 to process the snapshot
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
  // Retry on Conflict errors which can happen when L1 hasn't yet synced the latest GL0 state
  const lockAmount = 600000000000 // 6000 DAG
  let lockHash
  for (let attempt = 1; attempt <= 10; attempt++) {
    try {
      lockHash = await createTokenLock(account, urls, lockAmount)
      break
    } catch (err) {
      if (err.message && err.message.includes('Conflict') && attempt < 10) {
        logWorkflow.info(`Token lock creation hit Conflict (attempt ${attempt}/5), waiting for L1 sync...`)
        await sleep(5000)
      } else {
        throw err
      }
    }
  }
  logWorkflow.info(`Created token lock for withdrawal test: ${lockHash}`)

  const stakeHash = await createDelegatedStake(account, lockHash, lockAmount, nodeId)
  logWorkflow.info(`Created delegated stake: ${stakeHash}`)

  // Wait for stake to be included using ordinal-aware retry
  logWorkflow.info('Waiting for stake inclusion in global snapshot...')
  const stake = await waitForStakeInclusion(urls, account.address, stakeHash)
  logWorkflow.info(`Stake confirmed in snapshot: ${stake.hash.substring(0, 16)}...`)

  // Initiate withdrawal
  await withdrawDelegatedStake(account, stakeHash)
  logWorkflow.info('Initiated stake withdrawal')

  // Wait for stake to move to pendingWithdrawals using ordinal-aware retry
  const pending = await waitForStakeWithdrawal(urls, account.address, stakeHash)
  logWorkflow.info('Stake confirmed in pendingWithdrawals')

  // Wait for L1 state sync (ordinal-aware retry ensures GL0 has progressed)
  logWorkflow.info('Waiting for L1 state sync after withdrawal...')
  await sleep(5000) // Allow time for state propagation

  // Check if token lock is still in activeTokenLocks on GL0
  // The behavior during withdrawal may vary based on timing:
  // - Token lock may still be active (replacement succeeds)
  // - Token lock may be removed (NothingToReplace)
  const activeTokenLocks = await getActiveTokenLocks(urls, account.address)
  const isLockActive = activeTokenLocks.some(lock => lock.hash === lockHash)
  logWorkflow.info(`Token lock active status after withdrawal: ${isLockActive}`)

  const newAmount = lockAmount + 100000000000 // +1000 DAG
  
  if (isLockActive) {
    // Token lock still present - replacement should succeed
    // However, due to TOCTOU race between GL0 check and L1 processing,
    // the lock may be removed before our replacement is processed.
    // Accept either outcome when lock was active at check time.
    logWorkflow.info('Token lock still active, expecting replacement to succeed...')
    try {
      const replacementHash = await createTokenLock(account, urls, newAmount, lockHash, lockAmount)
      if (!replacementHash || replacementHash.length !== 64) {
        throw new Error('Expected valid replacement hash when token lock is active')
      }
      logWorkflow.info(`Replacement succeeded as expected: ${replacementHash}`)
    } catch (error) {
      // TOCTOU race: lock was removed between our check and the replacement
      if (error.message && error.message.includes('NothingToReplace')) {
        logWorkflow.info('Race condition: lock removed after check but before replacement (acceptable)')
      } else {
        throw error // Re-throw unexpected errors
      }
    }
  } else {
    // Token lock removed - replacement should fail with NothingToReplace
    logWorkflow.info('Token lock not active, expecting NothingToReplace...')
    await createTokenLockExpectError(
      account,
      urls,
      newAmount,
      lockHash,
      'NothingToReplace'
    )
    logWorkflow.info('Token lock replacement correctly rejected (NothingToReplace)')
  }
  
  logWorkflow.info('Test verified system behaves consistently with observed state')

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
  } = extractKeysAndAccount(resolveNodeKeyPath('genesis-node', 0))

  const {
    privateKeyString: privateKeyString2,
    nodeId: nodeId2,
    account: account2,
  } = extractKeysAndAccount(resolveNodeKeyPath('validator-1', 1))

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

  // Wait for node-params to be included in a snapshot (up to 120s = ~3 consensus rounds at 43s each)
  let nodeParams = []
  for (let attempt = 1; attempt <= 12; attempt++) {
    await sleep(10000)
    nodeParams = await getNodeParams(urls)
    logWorkflow.info(`Node parameters configured: ${nodeParams.length} nodes (attempt ${attempt}/12)`)
    if (nodeParams.length >= 2) break
  }

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

  // Wait for 2 ordinal progressions to ensure lock is fully propagated to L1.
  // One progression isn't enough with slow rounds (43s): the lock lands in ordinal N,
  // the global snapshot for N needs to reach L1, and L1 needs to process it.
  // Two progressions guarantees the lock's snapshot has been accepted and L1 is current.
  logWorkflow.info('Waiting for 2 ordinal progressions before sequential replacements...')
  // Compare against a FIXED baseline (first observed ordinal), not the previous
  // poll: on slow networks the ordinal advances by exactly 1 between polls, so
  // a consecutive-poll delta of 2 is unsatisfiable even though rounds progress.
  let baselineOrdinal = null
  await withRetryOrdinal(
    async ({ ordinal }) => {
      if (baselineOrdinal === null) {
        baselineOrdinal = ordinal
        throw new Error('Waiting for first ordinal')
      }
      if (ordinal - baselineOrdinal < 2) throw new Error(`Waiting for 2 ordinal progressions: ${ordinal}`)
      return true
    },
    { globalL0Url: urls.globalL0Url, name: 'waitForLockPropagation', maxOrdinalMisses: 10, maxStalledChecks: 60 }
  )
  await sleep(5000) // Extra buffer for L1 to process the snapshot

  // Test 5: Multiple sequential replacements
  await testMultipleSequentialReplacements(urls, account, newLockHash, newAmount, stakeHash)

  // Wait for L1 to sync latest global snapshots before next test to avoid ordinal conflicts
  logWorkflow.info('Waiting for L1 sync before withdrawal test...')
  await sleep(10000)

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
