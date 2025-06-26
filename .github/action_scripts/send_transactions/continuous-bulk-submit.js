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

// Poll GL0 for current ordinal
const getCurrentOrdinal = async (l0Url) => {
  try {
    const response = await fetch(`${l0Url}/cluster/info`)
    const data = await response.json()
    return data.ordinal || 0
  } catch (error) {
    logMessage(`Error fetching ordinal: ${error.message}`)
    return null
  }
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

const continuousBulkSubmit = async () => {
  const args = process.argv.slice(2)
  
  if (args.length < 2) {
    throw new Error('Usage: node continuous-bulk-submit.js <dagl0-port-prefix> <dagl1-port-prefix>')
  }
  
  const dagL0PortPrefix = args[0]
  const dagL1PortPrefix = args[1]
  
  const networkConfig = {
    l0Url: `http://localhost:${dagL0PortPrefix}0`,
    l1Url: `http://localhost:${dagL1PortPrefix}0`,
  }
  
  logMessage('Loading private keys...')
  const keys = loadPrivateKeys()
  
  if (keys.length < 3) {
    throw new Error(`Expected at least 3 keys but found ${keys.length}. Run 'just generate-test-keys' first.`)
  }
  
  logMessage(`Found ${keys.length} keys`)
  keys.forEach(key => {
    logMessage(`Key ${key.index}: ${key.address}`)
  })
  
  logMessage('Setting up accounts...')
  const accounts = await setupAccounts(keys, networkConfig)
  
  // Check initial balances
  for (const acc of accounts) {
    const balance = await acc.account.getBalance()
    logMessage(`Account ${acc.index} (${acc.address}) balance: ${balance}`)
  }
  
  let lastOrdinal = await getCurrentOrdinal(networkConfig.l0Url)
  let currentAccountIndex = 0
  let transactionCount = 0
  
  logMessage(`Starting continuous transaction submission. Initial ordinal: ${lastOrdinal}`)
  
  // Main loop
  while (true) {
    const currentOrdinal = await getCurrentOrdinal(networkConfig.l0Url)
    
    if (currentOrdinal === null) {
      logMessage('Failed to fetch ordinal, waiting...')
      await sleep(1000)
      continue
    }
    
    // Check if ordinal has changed
    if (currentOrdinal !== lastOrdinal) {
      logMessage(`Ordinal changed from ${lastOrdinal} to ${currentOrdinal}`)
      lastOrdinal = currentOrdinal
      
      // Submit transaction from current account to next account
      const fromAccount = accounts[currentAccountIndex]
      const toAccount = accounts[(currentAccountIndex + 1) % accounts.length]
      
      logMessage(`Submitting transaction from account ${fromAccount.index} to account ${toAccount.index}`)
      
      const hash = await submitTransaction(
        fromAccount.account,
        toAccount.address,
        1, // amount
        0  // fee
      )
      
      if (hash) {
        transactionCount++
        logMessage(`Transaction ${transactionCount} submitted successfully. Hash: ${hash}`)
        
        // Move to next account in rotation
        currentAccountIndex = (currentAccountIndex + 1) % accounts.length
      } else {
        logMessage('Transaction submission failed')
      }
    }
    
    // Short delay before polling again
    await sleep(500)
  }
}

// Run the script
continuousBulkSubmit().catch((err) => {
  logMessage(`Script failed: ${err.message}`)
  console.error(err)
  process.exit(1)
})