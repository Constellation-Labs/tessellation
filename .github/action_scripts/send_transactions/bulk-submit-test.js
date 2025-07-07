const { dag4 } = require('@stardust-collective/dag4')
const fs = require('fs')
const path = require('path')
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
  }
  
  return accounts
}

// Submit a transaction from one account to another
const submitTransaction = async (fromAccount, toAddress, amount = 1, fee = 0) => {
  try {
    const hash = await fromAccount.sendTransaction(toAddress, amount, fee)
    return hash
  } catch (error) {
    logMessage(`Error submitting transaction: ${error.message}`)
    return null
  }
}

const bulkSubmitTest = async () => {
  const args = process.argv.slice(2)
  
  if (args.length < 2) {
    throw new Error('Usage: node bulk-submit-test.js <dagl0-port-prefix> <dagl1-port-prefix>')
  }
  
  const dagL0PortPrefix = args[0]
  const dagL1PortPrefix = args[1]
  
  const networkConfig = {
    l0Url: `http://localhost:${dagL0PortPrefix}0`,
    l1Url: `http://localhost:${dagL1PortPrefix}0`,
  }
  
  logMessage('Loading private keys...')
  const keys = loadPrivateKeys()
  
  // We need at least 30 keys for this test
  const requiredKeys = 30
  if (keys.length < requiredKeys) {
    logMessage(`WARNING: Found only ${keys.length} keys, but need ${requiredKeys} for optimal test. Will cycle through available keys.`)
  }
  
  logMessage(`Found ${keys.length} keys`)
  
  logMessage('Setting up accounts...')
  const accounts = await setupAccounts(keys.slice(0, Math.min(keys.length, requiredKeys)), networkConfig)
  
  // Check initial balances
  for (const acc of accounts) {
    const balance = await acc.account.getBalance()
    logMessage(`Account ${acc.index} (${acc.address}) balance: ${balance}`)
  }
  
  logMessage(`Starting bulk submit test. Will submit ${requiredKeys} transactions, one every 5 seconds.`)
  
  let transactionCount = 0
  const submittedTransactions = []
  
  // Submit transactions rotating through available accounts
  for (let i = 0; i < requiredKeys; i++) {
    const fromAccountIndex = i % accounts.length
    const toAccountIndex = (i + 1) % accounts.length
    
    const fromAccount = accounts[fromAccountIndex]
    const toAccount = accounts[toAccountIndex]
    
    logMessage(`[${i + 1}/${requiredKeys}] Submitting transaction from account ${fromAccount.index} to account ${toAccount.index}`)
    
    const startTime = Date.now()
    const hash = await submitTransaction(
      fromAccount.account,
      toAccount.address,
      1, // amount
      0  // fee
    )
    
    if (hash) {
      transactionCount++
      submittedTransactions.push({
        hash,
        from: fromAccount.address,
        to: toAccount.address,
        timestamp: new Date().toISOString()
      })
      logMessage(`Transaction ${transactionCount} submitted successfully. Hash: ${hash}`)
    } else {
      logMessage(`Transaction ${i + 1} submission failed`)
    }
    
    // Wait 5 seconds before next transaction (unless it's the last one)
    if (i < requiredKeys - 1) {
      const elapsedTime = Date.now() - startTime
      const waitTime = Math.max(0, 5000 - elapsedTime)
      if (waitTime > 0) {
        logMessage(`Waiting ${waitTime}ms before next transaction...`)
        await sleep(waitTime)
      }
    }
  }
  
  logMessage(`Bulk submit test completed. Successfully submitted ${transactionCount}/${requiredKeys} transactions.`)
  
  // Final balance check
  logMessage('Final account balances:')
  for (const acc of accounts) {
    const balance = await acc.account.getBalance()
    logMessage(`Account ${acc.index} (${acc.address}) balance: ${balance}`)
  }
  
  // Summary
  logMessage('\n=== Test Summary ===')
  logMessage(`Total transactions submitted: ${transactionCount}`)
  logMessage(`Total time: ~${requiredKeys * 5} seconds`)
  logMessage(`Success rate: ${((transactionCount / requiredKeys) * 100).toFixed(1)}%`)
  
  if (transactionCount < requiredKeys) {
    throw new Error(`Failed to submit all transactions. Only ${transactionCount}/${requiredKeys} succeeded.`)
  }
  
  logMessage('Bulk submit test passed!')
}

// Run the script
bulkSubmitTest().catch((err) => {
  logMessage(`Script failed: ${err.message}`)
  console.error(err)
  process.exit(1)
})