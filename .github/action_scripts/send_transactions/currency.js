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

const SLEEP_TIME_UNTIL_QUERY = 30 * 1000

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

    logMessage(`Waiting ${SLEEP_TIME_UNTIL_QUERY} ms to fetch wallet balances`)
    await sleep(SLEEP_TIME_UNTIL_QUERY)

    const originBalance = await origin.getBalance()
    const destinationBalance = await destination.getBalance()

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

    logMessage(`Waiting ${SLEEP_TIME_UNTIL_QUERY} ms to fetch wallet balances`)
    await sleep(SLEEP_TIME_UNTIL_QUERY)

    const originBalance = await metagraphTokenClient.getBalance()
    const destinationBalance = await metagraphTokenClient.getBalanceFor(
      destination.address,
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

    logMessage(
      `Waiting ${SLEEP_TIME_UNTIL_QUERY}ms until fetch wallet balances`,
    )
    await sleep(SLEEP_TIME_UNTIL_QUERY)

    const balance1 = await sendingClient.getBalanceFor(FIRST_WALLET_ADDRESS)
    const balance2 = await sendingClient.getBalanceFor(SECOND_WALLET_ADDRESS)
    const balance3 = await sendingClient.getBalanceFor(THIRD_WALLET_ADDRESS)

    logMessage(`FirstWalletBalance: ${balance1}`)
    logMessage(`SecondWalletBalance: ${balance2}`)
    logMessage(`ThirdWalletBalance: ${balance3}`)
    logMessage(`firstToSecondSucceeded: ${firstToSecondSucceeded}`)
    logMessage(`firstToThirdSucceeded: ${firstToThirdSucceeded}`)

    if (
      firstToSecondSucceeded &&
      balance1 === startBalance1 - sendAmount - sendFee &&
      balance2 === startBalance2 + sendAmount &&
      balance3 === startBalance3
    ) {
      logMessage(`No double spend: Amount sent to second wallet`)
      return
    }

    if (
      firstToThirdSucceeded &&
      balance1 === startBalance1 - sendAmount - sendFee &&
      balance2 === startBalance2 &&
      balance3 === startBalance3 + sendAmount
    ) {
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

  const { originBalance, destinationBalance } = await batchFunc(
    metagraphOpts,
    fromAccount,
    toAccount,
    amount,
    fee,
    txnCount,
  )

  const totalAmount = txnCount * amount
  const totalFee = txnCount * fee

  const expectedFromBalance = fromAccountStart - totalAmount - totalFee
  const expectedToBalance = toAccountStart + totalAmount

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
    l0GlobalUrl: `http://localhost:${dagL0PortPrefix}00`,
    dagL1UrlFirstNode: `http://localhost:${dagL1PortPrefix}00`,
    l0MetagraphUrl: `http://localhost:${metagraphL0PortPrefix}00`,
    l1MetagraphUrl: `http://localhost:${currencyL1PortPrefix}00`,
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
