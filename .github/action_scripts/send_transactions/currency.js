const { dag4 } = require('@stardust-collective/dag4')
const { parseSharedArgs, logWorkflow } = require('../shared')

const createConfig = () => {
  const args = process.argv.slice(2)

  if (args.length < 5) {
    throw new Error(
      'Usage: node script.js <dagl0-port-prefix> <dagl1-port-prefix> <ml0-port-prefix> <cl1-port-prefix> <datal1-port-prefix>',
    )
  }

  const sharedArgs = parseSharedArgs(args.slice(0, 5))
  return { ...sharedArgs }
}

const BALANCE_QUERY_TIMEOUT = 4 * 60 * 1000
const BALANCE_QUERY_INTERVAL = 5 * 1000

const FIRST_WALLET_SEED_PHRASE =
  'right off artist rare copy zebra shuffle excite evidence mercy isolate raise'
const SECOND_WALLET_SEED_PHRASE =
  'gauge shell cactus system resemble garlic pioneer theme doll grocery tiger spend'

const FIRST_WALLET_ADDRESS = 'DAG0d6yzQqBZTCnq7kB9hL8p4cCiFejfM5m6FBJB'
const SECOND_WALLET_ADDRESS = 'DAG87hragrbzrEQEz6VC5B7hvtm4wAemS7Zg8KFj'
const THIRD_WALLET_ADDRESS = 'DAG0DQPuvVThrHnz66S4V6cocrtpg59oesAWyRMb'

const logMessage = (message) => {
  logWorkflow.info(message)
}

const sleep = (ms) => {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

const balancesMatch = (actualBalances, expectedBalances) =>
  actualBalances.length === expectedBalances.length &&
  actualBalances.every(
    (balance, idx) => Number(balance) === Number(expectedBalances[idx]),
  )

const waitForBalances = async (label, fetchBalances, expectedBalances) => {
  const deadline = Date.now() + BALANCE_QUERY_TIMEOUT
  let lastBalances = []
  let attempt = 0

  while (Date.now() <= deadline) {
    attempt += 1
    lastBalances = await fetchBalances()

    if (balancesMatch(lastBalances, expectedBalances)) {
      logMessage(
        `${label} balances reached expected values on attempt ${attempt}: ${lastBalances.join(
          ', ',
        )}`,
      )
      return lastBalances
    }

    logMessage(
      `${label} balances not ready on attempt ${attempt}; expected ${expectedBalances.join(
        ', ',
      )}, got ${lastBalances.join(', ')}`,
    )
    await sleep(BALANCE_QUERY_INTERVAL)
  }

  throw Error(
    `${label} balances did not reach expected values within ${BALANCE_QUERY_TIMEOUT} ms; expected ${expectedBalances.join(
      ', ',
    )}, got ${lastBalances.join(', ')}`,
  )
}

const waitForAnyBalanceMatch = async (label, fetchBalances, predicates) => {
  const deadline = Date.now() + BALANCE_QUERY_TIMEOUT
  let lastBalances = []
  let attempt = 0

  while (Date.now() <= deadline) {
    attempt += 1
    lastBalances = await fetchBalances()
    const matched = predicates.find(({ matches }) => matches(lastBalances))

    if (matched) {
      logMessage(
        `${label} balances reached expected ${matched.description} state on attempt ${attempt}: ${lastBalances.join(
          ', ',
        )}`,
      )
      return { balances: lastBalances, matched }
    }

    logMessage(
      `${label} balances not ready on attempt ${attempt}; got ${lastBalances.join(
        ', ',
      )}`,
    )
    await sleep(BALANCE_QUERY_INTERVAL)
  }

  throw Error(
    `${label} balances did not reach any expected state within ${BALANCE_QUERY_TIMEOUT} ms; got ${lastBalances.join(
      ', ',
    )}`,
  )
}

const batchTransaction = async (
  origin,
  destination,
  amount = 10,
  fee = 1,
  num = 100,
) => {
  try {
    const txnsData = []
    for (let idx = 0; idx < num; idx++) {
      const txnBody = {
        address: destination.address,
        amount,
        fee,
      }

      txnsData.push(txnBody)
    }

    const generatedTransactions = await origin.generateBatchTransactions(
      txnsData,
    )

    const hashes = await origin.sendBatchTransactions(generatedTransactions)

    logMessage(
      `DAG transaction from: ${origin.address} sent - batch of ${num}.`,
    )

    return hashes
  } catch (e) {
    throw Error(`Error when sending batch transaction: ${e}`)
  }
}

const batchMetagraphTransaction = async (
  metagraphTokenClient,
  origin,
  destination,
  amount = 10,
  fee = 1,
  num = 100,
) => {
  try {
    const txnsData = []
    for (let idx = 0; idx < num; idx++) {
      const txnBody = {
        address: destination.address,
        amount,
        fee,
      }

      txnsData.push(txnBody)
    }

    const generatedTransactions = await metagraphTokenClient.generateBatchTransactions(
      txnsData,
    )

    const hashes = await metagraphTokenClient.sendBatchTransactions(
      generatedTransactions,
    )

    logMessage(
      `L0 token transaction from: ${origin.address} sent - batch of ${num}.`,
    )

    return hashes
  } catch (e) {
    throw Error(`Error when sending batch transaction: ${e}`)
  }
}

const handleBatchTransactions = async (
  networkOptions,
  origin,
  destination,
  amount,
  fee,
  txnCount,
  expectedOriginBalance,
  expectedDestinationBalance,
) => {
  if (networkOptions) {
    await origin.connect({
      networkVersion: '2.0',
      l0Url: networkOptions.l0GlobalUrl,
      l1Url: networkOptions.dagL1UrlFirstNode,
      testnet: true,
    })
  }

  try {
    await batchTransaction(origin, destination, amount, fee, txnCount)

    const [originBalance, destinationBalance] = await waitForBalances(
      'DAG transfer',
      async () => [await origin.getBalance(), await destination.getBalance()],
      [expectedOriginBalance, expectedDestinationBalance],
    )

    return { originBalance, destinationBalance }
  } catch (error) {
    const errorMessage = `Error when sending transactions between wallets, message: ${error}`
    logMessage(errorMessage)
    throw error
  }
}

const handleMetagraphBatchTransactions = async (
  networkOptions,
  origin,
  destination,
  amount,
  fee,
  txnCount,
  expectedOriginBalance,
  expectedDestinationBalance,
) => {
  try {
    await origin.connect({
      networkVersion: '2.0',
      l0Url: networkOptions.l0GlobalUrl,
      l1Url: networkOptions.dagL1UrlFirstNode,
      testnet: true,
    })

    const metagraphTokenClient = origin.createMetagraphTokenClient({
      id: networkOptions.metagraphId,
      l0Url: networkOptions.l0MetagraphUrl,
      l1Url: networkOptions.l1MetagraphUrl,
      testnet: true,
    })

    await batchMetagraphTransaction(
      metagraphTokenClient,
      origin,
      destination,
      amount,
      fee,
      txnCount,
    )

    const [originBalance, destinationBalance] = await waitForBalances(
      'Metagraph transfer',
      async () => [
        await metagraphTokenClient.getBalance(),
        await metagraphTokenClient.getBalanceFor(destination.address),
      ],
      [expectedOriginBalance, expectedDestinationBalance],
    )

    return { originBalance, destinationBalance }
  } catch (error) {
    const errorMessage = `Error when sending transactions between wallets, message: ${error}`
    logMessage(errorMessage)
    throw error
  }
}

const doubleSpendTest = async (networkOptions, isMetagraph) => {
  logMessage(
    `========= Starting double spend transaction test (${
      isMetagraph ? 'L0 token' : 'DAG'
    }) =========`,
  )

  const sendAmount = 1000
  const sendFee = 0

  const connectConfig = {
    networkVersion: '2.0',
    l0Url: networkOptions.l0GlobalUrl,
    l1Url: networkOptions.dagL1UrlFirstNode,
    testnet: true,
  }

  const accountFirstNode = dag4.createAccount()
  accountFirstNode.loginSeedPhrase(FIRST_WALLET_SEED_PHRASE)
  accountFirstNode.connect(connectConfig)

  let sendingClient
  if (isMetagraph) {
    sendingClient = accountFirstNode.createMetagraphTokenClient({
      id: networkOptions.metagraphId,
      l0Url: networkOptions.l0MetagraphUrl,
      l1Url: networkOptions.l1MetagraphUrl,
    })
  } else {
    sendingClient = accountFirstNode
  }

  const lastRef = await sendingClient.network.getAddressLastAcceptedTransactionRef(
    FIRST_WALLET_ADDRESS,
  )

  const firstToSecondTx = await accountFirstNode.generateSignedTransaction(
    SECOND_WALLET_ADDRESS,
    sendAmount,
    sendFee,
    lastRef,
  )

  const firstToThirdTx = await accountFirstNode.generateSignedTransaction(
    THIRD_WALLET_ADDRESS,
    sendAmount,
    sendFee,
    lastRef,
  )

  try {
    const startBalance1 = await sendingClient.getBalanceFor(
      FIRST_WALLET_ADDRESS,
    )
    const startBalance2 = await sendingClient.getBalanceFor(
      SECOND_WALLET_ADDRESS,
    )
    const startBalance3 = await sendingClient.getBalanceFor(
      THIRD_WALLET_ADDRESS,
    )

    logMessage('Sending txns w/same lastRef')
    const [firstToSecondSucceeded, firstToThirdSucceeded] = await Promise.all([
      sendingClient.network
        .postTransaction(firstToSecondTx)
        .then((v) => true)
        .catch((e) => false),
      sendingClient.network
        .postTransaction(firstToThirdTx)
        .then((v) => true)
        .catch((e) => false),
    ])

    const secondWalletState = {
      description: 'second-wallet',
      matches: ([balance1, balance2, balance3]) =>
        firstToSecondSucceeded &&
        balance1 === startBalance1 - sendAmount - sendFee &&
        balance2 === startBalance2 + sendAmount &&
        balance3 === startBalance3,
    }
    const thirdWalletState = {
      description: 'third-wallet',
      matches: ([balance1, balance2, balance3]) =>
        firstToThirdSucceeded &&
        balance1 === startBalance1 - sendAmount - sendFee &&
        balance2 === startBalance2 &&
        balance3 === startBalance3 + sendAmount,
    }
    const { balances: [balance1, balance2, balance3], matched } = await waitForAnyBalanceMatch(
      'Double-spend',
      async () => [
        await sendingClient.getBalanceFor(FIRST_WALLET_ADDRESS),
        await sendingClient.getBalanceFor(SECOND_WALLET_ADDRESS),
        await sendingClient.getBalanceFor(THIRD_WALLET_ADDRESS),
      ],
      [secondWalletState, thirdWalletState],
    )

    logMessage(`FirstWalletBalance: ${balance1}`)
    logMessage(`SecondWalletBalance: ${balance2}`)
    logMessage(`ThirdWalletBalance: ${balance3}`)
    logMessage(`firstToSecondSucceeded: ${firstToSecondSucceeded}`)
    logMessage(`firstToThirdSucceeded: ${firstToThirdSucceeded}`)

    if (matched === secondWalletState) {
      logMessage(`No double spend: Amount sent to second wallet`)
      return
    }

    if (matched === thirdWalletState) {
      logMessage(`No double spend: Amount sent to third wallet`)
      return
    }

    throw Error(`Double spend occurred`)
  } catch (error) {
    const errorMessage = `Error when sending double spend transaction between wallets, message: ${error}`
    logMessage(errorMessage)
    throw error
  }
}

const assertBalances = async (
  account1Balance,
  account2Balance,
  expectedAccount1Balance,
  expectedAccount2Balance,
) => {
  if (
    Number(account1Balance) !== Number(expectedAccount1Balance) ||
    Number(account2Balance) !== Number(expectedAccount2Balance)
  ) {
    throw Error(`
        Error sending transactions. Wallet balances are different than expected:
        expectedAccount1Balance: ${expectedAccount1Balance} ---- actual: ${account1Balance}
        expectedAccount2Balance: ${expectedAccount2Balance} ---- actual: ${account2Balance}
        `)
  }

  logMessage(`Correct Account 1 Balance: ${expectedAccount1Balance}`)
  logMessage(`Correct Account 2 Balance: ${expectedAccount2Balance}`)
}

const transferTest = async (
  fromAccount,
  toAccount,
  amount,
  fee,
  txnCount,
  metagraphOpts,
) => {
  let fromAccountStart, toAccountStart, isMetagraph
  if (metagraphOpts) {
    isMetagraph = true

    const metagraphTokenClient = fromAccount.createMetagraphTokenClient({
      id: metagraphOpts.metagraphId,
      l0Url: metagraphOpts.l0MetagraphUrl,
      l1Url: metagraphOpts.l1MetagraphUrl,
    })

    fromAccountStart = await metagraphTokenClient.getBalance()
    toAccountStart = await metagraphTokenClient.getBalanceFor(toAccount.address)
  } else {
    fromAccountStart = await fromAccount.getBalance()
    toAccountStart = await toAccount.getBalance()
  }

  logMessage(
    `========= Transfer test (${isMetagraph ? 'L0 token' : 'DAG'}): ${
      fromAccount.address
    } to ${toAccount.address} w/fee (${fee}) and count (${txnCount}) =========`,
  )

  const batchFunc = metagraphOpts
    ? handleMetagraphBatchTransactions
    : handleBatchTransactions

  const totalAmount = txnCount * amount
  const totalFee = txnCount * fee
  const expectedFromBalance = fromAccountStart - totalAmount - totalFee
  const expectedToBalance = toAccountStart + totalAmount

  const { originBalance, destinationBalance } = await batchFunc(
    metagraphOpts,
    fromAccount,
    toAccount,
    amount,
    fee,
    txnCount,
    expectedFromBalance,
    expectedToBalance,
  )

  await assertBalances(
    originBalance,
    destinationBalance,
    expectedFromBalance,
    expectedToBalance,
  )
}

const sendTransactionsUsingUrls = async (networkOptions) => {
  const dagConfig = {
    networkVersion: '2.0',
    l0Url: networkOptions.l0GlobalUrl,
    l1Url: networkOptions.dagL1UrlFirstNode,
    testnet: true,
  }

  const account1 = dag4.createAccount()
  account1.loginSeedPhrase(FIRST_WALLET_SEED_PHRASE)
  account1.connect(dagConfig)

  const account2 = dag4.createAccount()
  account2.loginSeedPhrase(SECOND_WALLET_SEED_PHRASE)
  account2.connect(dagConfig)

  // DAG
  await transferTest(account1, account2, 10, 0, 1)
  await transferTest(account2, account1, 10, 0, 1)

  await transferTest(account1, account2, 10, 0.02, 100)
  await transferTest(account2, account1, 10, 0.02, 100)

  // Metagraph
  await transferTest(account1, account2, 10, 0, 1, networkOptions)
  await transferTest(account2, account1, 10, 0, 1, networkOptions)

  await transferTest(account1, account2, 10, 0.02, 100, networkOptions)
  await transferTest(account2, account1, 10, 0.02, 100, networkOptions)

  // Double spends
  await doubleSpendTest(networkOptions, false)
  await doubleSpendTest(networkOptions, true)

  logMessage('Script finished')
  return
}

const sendTransactions = async () => {
  const {
    dagL0PortPrefix,
    dagL1PortPrefix,
    metagraphL0PortPrefix,
    currencyL1PortPrefix,
  } = createConfig()

  const networkOptions = {
    metagraphId: 'custom_id',
    l0GlobalUrl: process.env.GL0_URL || `${process.env.TEST_HOST || 'http://localhost'}:${dagL0PortPrefix}00`,
    dagL1UrlFirstNode: process.env.GL1_URL || `${process.env.TEST_HOST || 'http://localhost'}:${dagL1PortPrefix}00`,
    l0MetagraphUrl: process.env.ML0_URL || `${process.env.TEST_HOST || 'http://localhost'}:${metagraphL0PortPrefix}00`,
    l1MetagraphUrl: process.env.CL1_URL || `${process.env.TEST_HOST || 'http://localhost'}:${currencyL1PortPrefix}00`,
  }

  await sendTransactionsUsingUrls(networkOptions)
}

sendTransactions().catch((err) => {
  if (process.env.RUN_ENV === 'local') {
    console.log('Failed: ')
    console.log(err)
    return
  }

  throw err
})
