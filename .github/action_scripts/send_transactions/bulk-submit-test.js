const { dag4 } = require('@stardust-collective/dag4')
const fs = require('fs')
const path = require('path')
const axios = require('axios')
const { logWorkflow } = require('../shared')

const logMessage = (message) => {
  logWorkflow.info(message)
}

const sleep = (ms) => {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

// Read private keys from the user test keys
const loadPrivateKeys = () => {
  const keys = []
  const userKeysDir = path.join(__dirname, '../../../docker/config/user-test-keys')
  
  // Check if user test keys exist, otherwise fall back to local test keys
  if (!fs.existsSync(userKeysDir)) {
    logMessage('User test keys not found, using local test keys instead')
    for (let i = 0; i < 3; i++) {
      const keyPath = path.join(__dirname, `../../../docker/config/local-test-keys/${i}/id_ecdsa.hex`)
      const addressPath = path.join(__dirname, `../../../docker/config/local-test-keys/${i}/address`)
      
      if (fs.existsSync(keyPath) && fs.existsSync(addressPath)) {
        const privateKey = fs.readFileSync(keyPath, 'utf8').trim()
        const address = fs.readFileSync(addressPath, 'utf8').trim()
        keys.push({ privateKey, address, index: i })
      }
    }
    return keys
  }
  
  // Load all available user test keys
  const files = fs.readdirSync(userKeysDir)
  const privateKeyFiles = files.filter(f => f.startsWith('private_key_') && f.endsWith('.hex'))
  
  privateKeyFiles.forEach(keyFile => {
    const index = parseInt(keyFile.match(/private_key_(\d+)\.hex/)[1])
    const keyPath = path.join(userKeysDir, keyFile)
    const addressPath = path.join(userKeysDir, `address_${index}.txt`)
    
    if (fs.existsSync(keyPath) && fs.existsSync(addressPath)) {
      const privateKey = fs.readFileSync(keyPath, 'utf8').trim()
      const address = fs.readFileSync(addressPath, 'utf8').trim()
      keys.push({ privateKey, address, index })
    }
  })
  
  // Sort by index to ensure consistent ordering
  keys.sort((a, b) => a.index - b.index)
  
  return keys
}

// Create and connect accounts from private keys
const setupAccounts = async (keys, networkConfig) => {
  const accounts = []
  
  for (const key of keys) {
    try {
      const account = dag4.createAccount()
      account.loginPrivateKey(key.privateKey)
      
      await account.connect({
        networkVersion: '2.0',
        l0Url: networkConfig.l0Url,
        l1Url: networkConfig.l1Url,
        testnet: true,
      })
      
      accounts.push({
        account,
        address: key.address,
        index: key.index
      })
    } catch (error) {
      logMessage(`Failed to setup account ${key.index}: ${error.message}`)
      throw error
    }
  }
  
  return accounts
}

// Submit a transaction from one account to another
const submitTransaction = async (fromAccount, toAddress, amount = 1, fee = 0) => {
  try {
    const result = await fromAccount.transferDag(toAddress, amount, fee)

    if (typeof result === 'object' && result !== null) {
      hash = result.hash
    }
    
    // logMessage(`[DEBUG] Transaction hash extracted: ${hash}`)
    return hash
  } catch (error) {
    logMessage(`Error submitting transaction: ${error.message}`)
    return null
  }
}

// Fetch global snapshot for a specific ordinal
const fetchSnapshot = async (l0Url, ordinal) => {
  try {
    const response = await axios.get(`${l0Url}/global-snapshots/${ordinal}`)
    return response.data
  } catch (error) {
    if (error.response && error.response.status === 404) {
      logMessage(`Snapshot for ordinal ${ordinal} not found (404)`)
    } else {
      logMessage(`Failed to fetch snapshot for ordinal ${ordinal}: ${error.message}`)
    }
    return null
  }
}

// Monitor ordinal changes in the background
const startOrdinalMonitor = async (l0Url, testStartTime) => {
  let previousOrdinal = null
  let monitoring = true
  let lastOrdinalChangeTime = Date.now()
  const ordinalDeltas = [] // Track all ordinal change deltas
  const encounteredOrdinals = new Set() // Track all ordinals we've seen
  const snapshots = new Map() // Store fetched snapshots
  const acceptedTransactionHashes = new Set() // Track accepted transaction hashes
  const submittedTransactionHashes = [] // Track submitted transaction hashes (will be populated externally)
  let totalTransactionsInSnapshots = 0
  
  const CHECK_INTERVAL_MS = 500 // Check every 500ms
  
  const getLatestSnapshot = async () => {
    try {
      const response = await axios.get(`${l0Url}/global-snapshots/latest`)
      return response.data
    } catch (error) {
      // Silent fail to avoid spamming logs
      return null
    }
  }
  
  const formatTime = (ms) => {
    const seconds = Math.floor(ms / 1000)
    const minutes = Math.floor(seconds / 60)
    const remainingSeconds = seconds % 60
    if (minutes > 0) {
      return `${minutes}m ${remainingSeconds}s`
    }
    return `${seconds}s`
  }
  
  const monitor = async () => {
    logMessage(`[ORDINAL MONITOR] Monitor started, polling ${l0Url}/global-snapshots/latest`)
    
    while (monitoring) {
      const snapshot = await getLatestSnapshot()
      
      if (snapshot && snapshot.value && snapshot.value.ordinal !== undefined) {
        const currentOrdinal = snapshot.value.ordinal
        const currentTime = Date.now()
        
        if (previousOrdinal === null) {
          logMessage(`[ORDINAL MONITOR] Initial ordinal: ${currentOrdinal}`)
          previousOrdinal = currentOrdinal
          lastOrdinalChangeTime = currentTime
          encounteredOrdinals.add(currentOrdinal)
        } else if (currentOrdinal !== previousOrdinal) {
          const timestamp = new Date().toISOString()
          const change = currentOrdinal - previousOrdinal
          const totalElapsed = currentTime - testStartTime
          const timeSinceLastChange = currentTime - lastOrdinalChangeTime

          const snapshot = await fetchSnapshot(l0Url, currentOrdinal)
          var totalTransactions = "?";
          var totalBlocks = "?";
          var numSigners = "?";
          if (snapshot.value) {
                        // Get total number of blocks
            totalBlocks = snapshot.value.blocks.length
            
            // Calculate total sum of transactions across all blocks
            totalTransactions = snapshot.value.blocks.reduce((sum, blockWrapper) => {
              // Each block has a transactions array
              return sum + (blockWrapper.block.value.transactions?.length || 0)
            }, 0)
            numSigners = snapshot.proofs.length
          }
          
          logMessage(`[ORDINAL] [${timestamp}] From: ${previousOrdinal} → ${currentOrdinal} (change: +${change}) time: ${formatTime(totalElapsed)} | TimeDelta: ${formatTime(timeSinceLastChange)} blocks: ${totalBlocks} | transactions: ${totalTransactions} | signers: ${numSigners}`)
          
          // Track this delta
          ordinalDeltas.push({
            fromOrdinal: previousOrdinal,
            toOrdinal: currentOrdinal,
            deltaMs: timeSinceLastChange,
            timestamp: currentTime
          })
          
          // Add new ordinal to encountered set
          encounteredOrdinals.add(currentOrdinal)
          
          previousOrdinal = currentOrdinal
          lastOrdinalChangeTime = currentTime
        }
      }
      
      await sleep(CHECK_INTERVAL_MS)
    }
  }
  
  // Start monitoring in background
  monitor().catch(err => {
    logMessage(`[ORDINAL MONITOR] Monitor error: ${err.message}`)
  })
  
  // Return stop function and data getter
  return {
    stop: () => {
      monitoring = false
    },
    getTimingData: () => ({
      ordinalDeltas,
      lastOrdinalChangeTime,
      encounteredOrdinals: Array.from(encounteredOrdinals).sort((a, b) => a - b),
      snapshots,
      acceptedTransactionHashes,
      totalTransactionsInSnapshots
    }),
    addSubmittedTransaction: (hash) => {
      submittedTransactionHashes.push(hash)
    }
  }
}

const bulkSubmitTest = async () => {
  // Configuration flags
  const ENABLE_TRANSACTIONS = true // Set to true to actually send transactions
  const numTransactionsToSend = 40 // Number of transactions to send
  
  const args = process.argv.slice(2)
  
  if (args.length < 2) {
    throw new Error('Usage: node bulk-submit-test.js <dagl0-port-prefix> <dagl1-port-prefix>')
  }
  
  const dagL0PortPrefix = args[0]
  const dagL1PortPrefix = args[1]
  
  // Validate port prefixes are numbers
  if (isNaN(parseInt(dagL0PortPrefix)) || isNaN(parseInt(dagL1PortPrefix))) {
    throw new Error('Port prefixes must be valid numbers')
  }
  
  const networkConfig = {
    l0Url: `http://localhost:${dagL0PortPrefix}00`,
    l1Url: `http://localhost:${dagL1PortPrefix}00`,
  }
  
  logMessage('Loading private keys...')
  const keys = loadPrivateKeys()
  
  logMessage(`Found ${keys.length} keys`)
  
  logMessage('Setting up accounts...')
  // Use all available keys instead of limiting to requiredKeys
  const accounts = ENABLE_TRANSACTIONS 
    ? await setupAccounts(keys, networkConfig)
    : keys.map((key, index) => ({ 
        account: null, 
        address: key.address, 
        index: key.index 
      }))

  const CHECK_INITIAL_BALANCES = false
  
  // Check initial balances
  if (ENABLE_TRANSACTIONS && CHECK_INITIAL_BALANCES) {
    for (const acc of accounts) {
      try {
        const balance = await acc.account.getBalance()
        logMessage(`Account ${acc.index} (${acc.address}) balance: ${balance}`)
      } catch (error) {
        logMessage(`Failed to get balance for account ${acc.index}: ${error.message}`)
      }
    }
  } else {
    logMessage('Skipping balance checks in debug mode')
  }
  
  submit_delay = 4000;
  logMessage(`Starting bulk submit test. Will submit ${numTransactionsToSend} transactions, one every ${submit_delay} milliseconds.`)
  logMessage(`Transaction sending is ${ENABLE_TRANSACTIONS ? 'ENABLED' : 'DISABLED (debug mode)'}`)
  
  // Track test start time
  const testStartTime = Date.now()
  
  // Start ordinal monitor
  logMessage('Starting ordinal monitor...')
  const ordinalMonitor = await startOrdinalMonitor(networkConfig.l0Url, testStartTime)
  
  let transactionCount = 0
  const submittedTransactions = []

  
  // Submit transactions rotating through available accounts
  for (let i = 0; i < numTransactionsToSend; i++) {
    const fromAccountIndex = i % accounts.length
    const toAccountIndex = (i + 1) % accounts.length
    
    const fromAccount = accounts[fromAccountIndex]
    const toAccount = accounts[toAccountIndex]
    
    const amount = 1

    
    const startTime = Date.now()
    let hash = null
    
    if (ENABLE_TRANSACTIONS) {
      hash = await submitTransaction(
        fromAccount.account,
        "DAG6kfTqFxLLPLopHqR43CeQrcvJ5k3eXgYSeELt",
        amount,
        0  // fee
      )
    } else {
      // Debug mode - simulate successful transaction
      hash = `debug_hash_${i + 1}`
      await sleep(10) // Small delay to simulate transaction time
    }
    if (hash) {
      transactionCount++
      submittedTransactions.push({
        hash,
        from: fromAccount.address,
        to: "DAG6kfTqFxLLPLopHqR43CeQrcvJ5k3eXgYSeELt",
        amount,
        timestamp: new Date().toISOString()
      })
      // Notify the ordinal monitor about this transaction
      ordinalMonitor.addSubmittedTransaction(hash)
      logMessage(`[${i + 1}/${numTransactionsToSend}] Submit from account ${fromAccount.index} amount: ${amount} hash ${hash}`)

    } else {
      logMessage(`Transaction ${i + 1} submission failed`)
    }
    
    // Wait 5 seconds before next transaction (unless it's the last one)
    if (i < numTransactionsToSend - 1) {
      const elapsedTime = Date.now() - startTime
      const waitTime = Math.max(0, submit_delay - elapsedTime)
      if (waitTime > 0) {
        await sleep(waitTime)
      }
    }
  }
  
  logMessage(`Bulk submit test completed. Successfully submitted ${transactionCount}/${numTransactionsToSend} transactions.`)
  
  // Final balance check
  if (ENABLE_TRANSACTIONS) {
    logMessage('Final account balances:')
    for (const acc of accounts) {
      try {
        const balance = await acc.account.getBalance()
        logMessage(`Account ${acc.index} (${acc.address}) balance: ${balance}`)
      } catch (error) {
        logMessage(`Failed to get final balance for account ${acc.index}: ${error.message}`)
      }
    }
  } else {
    logMessage('Skipping final balance checks in debug mode')
  }
  
  // Stop the ordinal monitor and get timing data
  ordinalMonitor.stop()
  await sleep(1000) // Give monitor time to finish
  
  const { ordinalDeltas, lastOrdinalChangeTime, encounteredOrdinals, snapshots, acceptedTransactionHashes, totalTransactionsInSnapshots } = ordinalMonitor.getTimingData()
  
  // Wait a bit for any final snapshots
  logMessage('\nWaiting 5 seconds for any final snapshots...')
  await sleep(5000)
  
  // Check for any missed ordinals at the end
  const latestSnapshot = await getLatestSnapshot(networkConfig.l0Url)
  if (latestSnapshot && latestSnapshot.value && latestSnapshot.value.ordinal) {
    const latestOrdinal = latestSnapshot.value.ordinal
    if (!encounteredOrdinals.includes(latestOrdinal)) {
      logMessage(`\nFetching final ordinal ${latestOrdinal}...`)
      const snapshot = await fetchSnapshot(networkConfig.l0Url, latestOrdinal)
      if (snapshot) {
        snapshots.set(latestOrdinal, snapshot)
        // Check for any final transactions
        if (snapshot.signed && snapshot.signed.value && snapshot.signed.value.blocks) {
          for (const blockWrapper of snapshot.signed.value.blocks) {
            if (blockWrapper.block && blockWrapper.block.signed && blockWrapper.block.signed.value && 
                blockWrapper.block.signed.value.transactions) {
              const transactions = blockWrapper.block.signed.value.transactions
              // TODO: Can't calculate hash here, instead use an assertion based on balance (based on round)
              // for (const txn of transactions) {
              //   const txHash = txn.hash || (txn.signed && txn.signed.hash)
              //   if (txHash) {
              //     const ourTxn = submittedTransactions.find(st => st.hash === txHash)
              //     if (ourTxn && !acceptedTransactionHashes.has(txHash)) {
              //       acceptedTransactionHashes.add(txHash)
              //     }
              //   }
              // }
            }
          }
        }
      }
    }
  }
  
  // Helper function to get latest snapshot
  async function getLatestSnapshot(l0Url) {
    try {
      const response = await axios.get(`${l0Url}/global-snapshots/latest`)
      return response.data
    } catch (error) {
      return null
    }
  }
  
  logMessage(`\n=== Final Transaction Summary ===`)
  logMessage(`Total snapshots fetched: ${snapshots.size}`)
  logMessage(`Total transactions in all snapshots: ${totalTransactionsInSnapshots}`)
  
  // Verify our transactions were accepted
  logMessage('\n=== Transaction Verification ===')
  

  const testEndTime = Date.now()
  const finalDelta = testEndTime - lastOrdinalChangeTime
  
  // Summary
  logMessage('\n=== Test Summary ===')
  logMessage(`Total transactions submitted: ${transactionCount}`)
  logMessage(`Total time: ~${numTransactionsToSend * 5} seconds`)
  logMessage(`Success rate: ${((transactionCount / numTransactionsToSend) * 100).toFixed(1)}%`)
  
  // Ordinal timing analysis
  logMessage('\n=== Ordinal Timing Analysis ===')
  logMessage(`Total ordinal changes: ${ordinalDeltas.length}`)
  
  if (ordinalDeltas.length > 0) {
    logMessage('\nOrdinal change deltas:')
    ordinalDeltas.forEach((delta, idx) => {
      const seconds = (delta.deltaMs / 1000).toFixed(1)
      logMessage(`  [${idx + 1}] Ordinal ${delta.fromOrdinal} → ${delta.toOrdinal}: ${seconds}s`)
    })
    
    const maxDelta = Math.max(...ordinalDeltas.map(d => d.deltaMs))
    const avgDelta = ordinalDeltas.reduce((sum, d) => sum + d.deltaMs, 0) / ordinalDeltas.length
    
    logMessage(`\nMax ordinal delta: ${(maxDelta / 1000).toFixed(1)}s`)
    logMessage(`Average ordinal delta: ${(avgDelta / 1000).toFixed(1)}s`)
  }
  
  logMessage(`\nFinal delta (test end to last ordinal): ${(finalDelta / 1000).toFixed(1)}s`)
  
  // Assertions
  const MAX_DELTA_SECONDS = 30
  const MAX_DELTA_MS = MAX_DELTA_SECONDS * 1000
  
  logMessage('\n=== Timing Assertions ===')
  
  // Check all ordinal deltas
  let allDeltasPass = true
  ordinalDeltas.forEach((delta, idx) => {
    if (delta.deltaMs > MAX_DELTA_MS) {
      logMessage(`❌ FAIL: Ordinal delta ${idx + 1} exceeded ${MAX_DELTA_SECONDS}s: ${(delta.deltaMs / 1000).toFixed(1)}s`)
      allDeltasPass = false
    }
  })
  
  if (allDeltasPass && ordinalDeltas.length > 0) {
    logMessage(`✅ PASS: All ${ordinalDeltas.length} ordinal deltas were under ${MAX_DELTA_SECONDS}s`)
  }
  
  // Check final delta
  if (finalDelta > MAX_DELTA_MS) {
    logMessage(`❌ FAIL: Final delta exceeded ${MAX_DELTA_SECONDS}s: ${(finalDelta / 1000).toFixed(1)}s`)
    logMessage(`   This indicates the network stopped producing ordinals`)
  } else {
    logMessage(`✅ PASS: Final delta was under ${MAX_DELTA_SECONDS}s: ${(finalDelta / 1000).toFixed(1)}s`)
  }
  
  // Overall test result
  if (transactionCount < numTransactionsToSend) {
    throw new Error(`Failed to submit all transactions. Only ${transactionCount}/${numTransactionsToSend} succeeded.`)
  }
  
  if (!allDeltasPass || finalDelta > MAX_DELTA_MS) {
    throw new Error('Test failed due to ordinal timing violations')
  }
  
  logMessage('\n✅ Bulk submit test passed!')
}

// Run the script
bulkSubmitTest().catch((err) => {
  logMessage(`Script failed: ${err.message}`)
  console.error(err)
  process.exit(1)
})