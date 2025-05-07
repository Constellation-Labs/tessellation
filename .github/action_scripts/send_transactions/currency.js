const { dag4 } = require('@stardust-collective/dag4')
const { parseSharedArgs } = require('../shared')

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

const SLEEP_TIME_UNTIL_QUERY = 120 * 1000

const FIRST_WALLET_SEED_PHRASE =
  'drift doll absurd cost upon magic plate often actor decade obscure smooth'
const SECOND_WALLET_SEED_PHRASE =
  'upper pistol movie hedgehog case exhaust wife injury joke live festival shield'

const FIRST_WALLET_ADDRESS = 'DAG4Zd2W2JxL1f1gsHQCoaKrRonPSSHLgcqD7osU'
const SECOND_WALLET_ADDRESS = 'DAG6kfTqFxLLPLopHqR43CeQrcvJ5k3eXgYSeELt'
const THIRD_WALLET_ADDRESS = 'DAG0DQPuvVThrHnz66S4V6cocrtpg59oesAWyRMb'

const logMessage = (message) => {
  const formattedMessage = {
    message,
  }
  console.log(formattedMessage)
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

    logMessage('Starting generation')
    const generatedTransactions = await origin.generateBatchTransactions(
      txnsData,
    )
    logMessage('Generated')

    logMessage('Starting sending')
    const hashes = await origin.sendBatchTransactions(generatedTransactions)
    logMessage('Sent')

    logMessage(
      `Transaction from: ${
        origin.address
      } sent - batch. Generated transaction response body: ${JSON.stringify(
        generatedTransactions,
      )}. Post hashes: ${hashes}`,
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

    logMessage('Starting generation')
    const generatedTransactions = await metagraphTokenClient.generateBatchTransactions(
      txnsData,
    )

    logMessage('Generated')
    logMessage('Starting sending')
    const hashes = await metagraphTokenClient.sendBatchTransactions(
      generatedTransactions,
    )
    logMessage('Sent')

    logMessage(
      `Transaction from: ${
        origin.address
      } sent - batch. Generated transaction response body: ${JSON.stringify(
        generatedTransactions,
      )}. Post hashes: ${hashes}`,
    )

    return hashes
  } catch (e) {
    throw Error(`Error when sending batch transaction: ${e}`)
  }
}

const handleBatchTransactions = async (
  origin,
  destination,
  networkOptions,
  amount,
  fee,
  txnCount,
) => {
  await origin.connect({
    networkVersion: '2.0',
    l0Url: networkOptions.l0GlobalUrl,
    l1Url: networkOptions.dagL1UrlFirstNode,
    testnet: true,
  })

  try {
    await batchTransaction(origin, destination, amount, fee, txnCount)

    logMessage(
      `Waiting ${SLEEP_TIME_UNTIL_QUERY}ms until fetch wallet balances`,
    )
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
  origin,
  destination,
  networkOptions,
  amount,
  fee,
  txnCount,
) => {
  await origin.connect({
    networkVersion: '2.0',
    l0Url: networkOptions.l0GlobalUrl,
    l1Url: networkOptions.dagL1UrlFirstNode,
    testnet: true,
  })

  try {
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

    logMessage(
      `Waiting ${SLEEP_TIME_UNTIL_QUERY}ms until fetch wallet balances`,
    )
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

const sendDoubleSpendTransaction = async (networkOptions) => {
  const connectConfig = {
    networkVersion: '2.0',
    l0Url: networkOptions.l0GlobalUrl,
    l1Url: networkOptions.dagL1UrlFirstNode,
    testnet: true,
  }

  const sendAmount = 1000
  const sendFee = 1

  const lastRef = await dag4.network.getAddressLastAcceptedTransactionRef(
    FIRST_WALLET_ADDRESS,
  )

  const accountFirstNode = dag4.createAccount()
  accountFirstNode.loginSeedPhrase(FIRST_WALLET_SEED_PHRASE)
  accountFirstNode.connect(connectConfig)

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
    const startBalance1 = await accountFirstNode.getBalanceFor(
      FIRST_WALLET_ADDRESS,
    )
    const startBalance2 = await accountFirstNode.getBalanceFor(
      SECOND_WALLET_ADDRESS,
    )
    const startBalance3 = await accountFirstNode.getBalanceFor(
      THIRD_WALLET_ADDRESS,
    )

    const [firstToSecondSucceeded, firstToThirdSucceeded] = await Promise.all([
      dag4.network
        .postTransaction(firstToSecondTx)
        .catch((e) => false)
        .then((v) => true),
      dag4.network
        .postTransaction(firstToThirdTx)
        .catch((e) => false)
        .then((v) => true),
    ])

    logMessage(
      `Waiting ${SLEEP_TIME_UNTIL_QUERY}ms until fetch wallet balances`,
    )
    await sleep(SLEEP_TIME_UNTIL_QUERY)

    const balance1 = await accountFirstNode.getBalanceFor(FIRST_WALLET_ADDRESS)
    const balance2 = await accountFirstNode.getBalanceFor(SECOND_WALLET_ADDRESS)
    const balance3 = await accountFirstNode.getBalanceFor(THIRD_WALLET_ADDRESS)

    logMessage(`FirstWalletBalance: ${balance1}`)
    logMessage(`SecondWalletBalance: ${balance2}`)
    logMessage(`ThirdWalletBalance: ${balance3}`)

    if (
      firstToSecondSucceeded &&
      balance1 === startBalance1 - sendAmount &&
      balance2 === startBalance2 + sendAmount &&
      balance3 === startBalance3
    ) {
      logMessage(`No double spend: Amount sent to second wallet`)
      return
    }

    if (
      firstToThirdSucceeded &&
      balance1 === startBalance1 - sendAmount &&
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

const sendTransactionsUsingUrls = async (
  metagraphId,
  l0GlobalUrl,
  dagL1UrlFirstNode,
  dagL1UrlSecondNode,
  l0MetagraphUrl,
  l1MetagraphUrl,
) => {
  const account1 = dag4.createAccount()
  account1.loginSeedPhrase(FIRST_WALLET_SEED_PHRASE)

  const account2 = dag4.createAccount()
  account2.loginSeedPhrase(SECOND_WALLET_SEED_PHRASE)

  const networkOptions = {
    metagraphId,
    l0GlobalUrl,
    dagL1UrlFirstNode,
    dagL1UrlSecondNode,
    l0MetagraphUrl,
    l1MetagraphUrl,
  }

  const balances = {
    account1: Number(await account1.getBalance()),
    account2: Number(await account2.getBalance()),
  }

  try {
    logMessage(
      `Starting batch DAG Transactions from: ${account1.address} to ${account2.address}`,
    )
    const txnAmount1 = 10
    const txnFee1 = 0
    const txnCount1 = 100

    const { originBalance, destinationBalance } = await handleBatchTransactions(
      account1,
      account2,
      networkOptions,
      txnAmount1,
      txnFee1,
      txnCount1,
    )

    const expectedAccount1Balance = balances.account1 - txnCount1 * txnAmount1
    const expectedAccount2Balance = balances.account2 + txnCount1 * txnAmount1

    await assertBalances(
      originBalance,
      destinationBalance,
      expectedAccount1Balance,
      expectedAccount2Balance,
    )

    balances.account1 = expectedAccount1Balance
    balances.account2 = expectedAccount2Balance

    logMessage(
      `Finished batch DAG Transactions from: ${account1.address} to ${account2.address}`,
    )
  } catch (error) {
    logMessage(
      `Error sending forth transactions from: ${account1.address} to ${account2.address}:`,
      error,
    )
    throw error
  }

  try {
    logMessage(
      `Starting batch DAG Transactions from: ${account2.address} to ${account1.address}`,
    )
    const txnAmount2 = 10
    const txnFee2 = 0
    const txnCount2 = 100

    const { originBalance, destinationBalance } = await handleBatchTransactions(
      account2,
      account1,
      networkOptions,
      txnAmount2,
      txnFee2,
      txnCount2,
    )

    const expectedAccount1Balance = balances.account1 + txnCount2 * txnAmount2
    const expectedAccount2Balance = balances.account2 - txnCount2 * txnAmount2

    await assertBalances(
      destinationBalance,
      originBalance,
      expectedAccount1Balance,
      expectedAccount2Balance,
    )

    balances.account1 = expectedAccount1Balance
    balances.account2 = expectedAccount2Balance

    logMessage(
      `Finished batch DAG Transactions from: ${account2.address} to ${account1.address}`,
    )
  } catch (error) {
    logMessage(
      `Error sending back transactions from: ${account2.address} to ${account1.address}:`,
      error,
    )
    throw error
  }

  try {
    logMessage(
      `Starting batch METAGRAPH Transactions from: ${account1.address} to ${account2.address}`,
    )
    const txnAmount3 = 10
    const txnFee3 = 0
    const txnCount3 = 100

    const {
      originBalance,
      destinationBalance,
    } = await handleMetagraphBatchTransactions(
      account1,
      account2,
      networkOptions,
      txnAmount3,
      txnFee3,
      txnCount3,
    )

    const expectedAccount1Balance = balances.account1 - txnCount3 * txnAmount3
    const expectedAccount2Balance = balances.account2 + txnCount3 * txnAmount3

    await assertBalances(
      destinationBalance,
      originBalance,
      expectedAccount1Balance,
      expectedAccount2Balance,
    )

    balances.account1 = expectedAccount1Balance
    balances.account2 = expectedAccount2Balance

    logMessage(
      `Finished batch METAGRAPH Transactions from: ${account1.address} to ${account2.address}`,
    )
  } catch (error) {
    logMessage(
      `Error sending forth transactions from: ${account1.address} to ${account2.address}:`,
      error,
    )
    throw error
  }

  try {
    logMessage(
      `Starting batch METAGRAPH Transactions from: ${account2.address} to ${account1.address}`,
    )
    const txnAmount4 = 10
    const txnFee4 = 0
    const txnCount4 = 100

    const {
      originBalance,
      destinationBalance,
    } = await handleMetagraphBatchTransactions(
      account2,
      account1,
      networkOptions,
      txnAmount4,
      txnFee4,
      txnCount4,
    )

    const expectedAccount1Balance = balances.account1 + txnCount4 * txnAmount4
    const expectedAccount2Balance = balances.account2 - txnCount4 * txnAmount4

    await assertBalances(
      destinationBalance,
      originBalance,
      expectedAccount1Balance,
      expectedAccount2Balance,
    )

    balances.account1 = expectedAccount1Balance
    balances.account2 = expectedAccount2Balance

    logMessage(
      `Finished batch METAGRAPH Transactions from: ${account2.address} to ${account1.address}`,
    )
  } catch (error) {
    logMessage(
      `Error sending back transactions from: ${account2.address} to ${account1.address}:`,
      error,
    )
    throw error
  }

  try {
    logMessage('Starting to send double spend transaction')
    await sendDoubleSpendTransaction(networkOptions)

    logMessage('Finished double spend transaction')
  } catch (error) {
    logMessage(
      `Error sending back transactions from: ${account2.address} to ${account1.address}:`,
      error,
    )
    throw error
  }
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
  const metagraphId = 'custom_id'
  const l0GlobalUrl = `http://localhost:${dagL0PortPrefix}00`
  const dagL1UrlFirstNode = `http://localhost:${dagL1PortPrefix}00`
  const dagL1UrlSecondNode = `http://localhost:${dagL1PortPrefix}10`
  const l0MetagraphUrl = `http://localhost:${metagraphL0PortPrefix}00`
  const l1MetagraphUrl = `http://localhost:${currencyL1PortPrefix}00`

  await sendTransactionsUsingUrls(
    metagraphId,
    l0GlobalUrl,
    dagL1UrlFirstNode,
    dagL1UrlSecondNode,
    l0MetagraphUrl,
    l1MetagraphUrl,
  )
}

sendTransactions()
