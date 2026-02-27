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
    const hash = await fromAccount.transferDag(toAddress, amount, fee)
    return hash
  } catch (error) {
    logMessage(`Error submitting transaction: ${error.message}`)
    return null
  }
}

// Monitor ordinal changes in the background
const startOrdinalMonitor = async (l0Url, testStartTime) => {
  let previousOrdinal = null
  let monitoring = true
  let lastOrdinalChangeTime = Date.now()
  
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
        } else if (currentOrdinal !== previousOrdinal) {
          const timestamp = new Date().toISOString()
          const change = currentOrdinal - previousOrdinal
          const totalElapsed = currentTime - testStartTime
          const timeSinceLastChange = currentTime - lastOrdinalChangeTime
          
          logMessage(`[ORDINAL MONITOR] [${timestamp}] Ordinal changed: ${previousOrdinal} → ${currentOrdinal} (change: +${change}) | Total test time: ${formatTime(totalElapsed)} | Time since last change: ${formatTime(timeSinceLastChange)}`)
          
          previousOrdinal = currentOrdinal
          lastOrdinalChangeTime = currentTime
        }
      }
      
      await sleep(500)
    }
  }
  
  // Start monitoring in background
  monitor().catch(err => {
    logMessage(`[ORDINAL MONITOR] Monitor error: ${err.message}`)
  })
  
  // Return stop function
  return () => {
    monitoring = false
  }
}

const bulkSubmitTest = async () => {
  // Configuration flags
  const ENABLE_TRANSACTIONS = true // Set to true to actually send transactions
  const numTransactionsToSend = 100 // Number of transactions to send
  
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
    l0Url: process.env.GL0_URL || `${process.env.TEST_HOST || 'http://localhost'}:${dagL0PortPrefix}00`,
    l1Url: process.env.GL1_URL || `${process.env.TEST_HOST || 'http://localhost'}:${dagL1PortPrefix}00`,
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
  
  // Check initial balances
  if (ENABLE_TRANSACTIONS) {
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
  
  logMessage(`Starting bulk submit test. Will submit ${numTransactionsToSend} transactions, one every 5 seconds.`)
  logMessage(`Transaction sending is ${ENABLE_TRANSACTIONS ? 'ENABLED' : 'DISABLED (debug mode)'}`)
  
  // Track test start time
  const testStartTime = Date.now()
  
  // Start ordinal monitor
  logMessage('Starting ordinal monitor...')
  const stopMonitor = await startOrdinalMonitor(networkConfig.l0Url, testStartTime)
  
  let transactionCount = 0
  const submittedTransactions = []
  
  // Submit transactions rotating through available accounts
  for (let i = 0; i < numTransactionsToSend; i++) {
    const fromAccountIndex = i % accounts.length
    const toAccountIndex = (i + 1) % accounts.length
    
    const fromAccount = accounts[fromAccountIndex]
    const toAccount = accounts[toAccountIndex]
    
    logMessage(`[${i + 1}/${numTransactionsToSend}] Submitting transaction from account ${fromAccount.index} to account ${toAccount.index}`)
    
    const startTime = Date.now()
    let hash = null
    
    if (ENABLE_TRANSACTIONS) {
      hash = await submitTransaction(
        fromAccount.account,
        toAccount.address,
        1, // amount
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
        to: toAccount.address,
        timestamp: new Date().toISOString()
      })
    } else {
      logMessage(`Transaction ${i + 1} submission failed`)
    }
    
    // Wait 5 seconds before next transaction (unless it's the last one)
    if (i < numTransactionsToSend - 1) {
      const elapsedTime = Date.now() - startTime
      const waitTime = Math.max(0, 5000 - elapsedTime)
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
  
  // Summary
  logMessage('\n=== Test Summary ===')
  logMessage(`Total transactions submitted: ${transactionCount}`)
  logMessage(`Total time: ~${numTransactionsToSend * 5} seconds`)
  logMessage(`Success rate: ${((transactionCount / numTransactionsToSend) * 100).toFixed(1)}%`)
  
  if (transactionCount < numTransactionsToSend) {
    throw new Error(`Failed to submit all transactions. Only ${transactionCount}/${numTransactionsToSend} succeeded.`)
  }
  
  logMessage('Bulk submit test passed!')
  
  // Stop the ordinal monitor
  stopMonitor()
  await sleep(1000) // Give monitor time to finish
}

// Run the script
bulkSubmitTest().catch((err) => {
  logMessage(`Script failed: ${err.message}`)
  console.error(err)
  process.exit(1)
})