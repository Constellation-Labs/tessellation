const { dag4 } = require("@stardust-collective/dag4");

const SLEEP_TIME_UNTIL_QUERY = 120 * 1000;

const FIRST_WALLET_SEED_PHRASE =
  "drift doll absurd cost upon magic plate often actor decade obscure smooth";
const SECOND_WALLET_SEED_PHRASE =
  "upper pistol movie hedgehog case exhaust wife injury joke live festival shield";

const FIRST_WALLET_ADDRESS = "DAG4Zd2W2JxL1f1gsHQCoaKrRonPSSHLgcqD7osU";
const SECOND_WALLET_ADDRESS = "DAG6kfTqFxLLPLopHqR43CeQrcvJ5k3eXgYSeELt";
const THIRD_WALLET_ADDRESS = "DAG0DQPuvVThrHnz66S4V6cocrtpg59oesAWyRMb";

const logMessage = (message) => {
  const formattedMessage = {
    message,
  };
  console.log(formattedMessage);
};

const sleep = (ms) => {
  return new Promise((resolve) => setTimeout(resolve, ms));
};

const batchTransaction = async (origin, destination) => {
  try {
    const txnsData = [];
    for (let idx = 0; idx < 100; idx++) {
      const txnBody = {
        address: destination.address,
        amount: 10,
        fee: 1,
      };

      txnsData.push(txnBody);
    }

    logMessage("Starting generation");
    const generatedTransactions = await origin.generateBatchTransactions(
      txnsData
    );
    logMessage("Generated");

    logMessage("Starting sending");
    const hashes = await origin.sendBatchTransactions(generatedTransactions);
    logMessage("Sent");

    logMessage(
      `Transaction from: ${
        origin.address
      } sent - batch. Generated transaction response body: ${JSON.stringify(
        generatedTransactions
      )}. Post hashes: ${hashes}`
    );

    return hashes;
  } catch (e) {
    throw Error(`Error when sending batch transaction: ${e}`);
  }
};

const batchMetagraphTransaction = async (
  metagraphTokenClient,
  origin,
  destination
) => {
  try {
    const txnsData = [];
    for (let idx = 0; idx < 100; idx++) {
      const txnBody = {
        address: destination.address,
        amount: 10,
        fee: 1,
      };

      txnsData.push(txnBody);
    }

    logMessage("Starting generation");
    const generatedTransactions =
      await metagraphTokenClient.generateBatchTransactions(txnsData);

    logMessage("Generated");
    logMessage("Starting sending");
    const hashes = await metagraphTokenClient.sendBatchTransactions(
      generatedTransactions
    );
    logMessage("Sent");

    logMessage(
      `Transaction from: ${origin} sent - batch. Generated transaction response body: ${JSON.stringify(
        generatedTransactions
      )}. Post hashes: ${hashes}`
    );

    return hashes;
  } catch (e) {
    throw Error(`Error when sending batch transaction: ${e}`);
  }
};

const handleBatchTransactions = async (origin, destination, networkOptions) => {
  await origin.connect({
    networkVersion: "2.0",
    l0Url: networkOptions.l0GlobalUrl,
    l1Url: networkOptions.dagL1UrlFirstNode,
    testnet: true,
  });

  try {
    await batchTransaction(origin, destination);

    logMessage(
      `Waiting ${SLEEP_TIME_UNTIL_QUERY}ms until fetch wallet balances`
    );
    await sleep(SLEEP_TIME_UNTIL_QUERY);

    const originBalance = await origin.getBalance();
    const destinationBalance = await destination.getBalance();

    return { originBalance, destinationBalance };
  } catch (error) {
    const errorMessage = `Error when sending transactions between wallets, message: ${error}`;
    logMessage(errorMessage);
    throw error;
  }
};

const handleMetagraphBatchTransactions = async (
  origin,
  destination,
  networkOptions
) => {
  await origin.connect({
    networkVersion: "2.0",
    l0Url: networkOptions.l0GlobalUrl,
    l1Url: networkOptions.dagL1UrlFirstNode,
    testnet: true,
  });

  try {
    const metagraphTokenClient = origin.createMetagraphTokenClient({
      id: networkOptions.metagraphId,
      l0Url: networkOptions.l0MetagraphUrl,
      l1Url: networkOptions.l1MetagraphUrl,
      testnet: true,
    });

    await batchMetagraphTransaction(metagraphTokenClient, origin, destination);

    logMessage(
      `Waiting ${SLEEP_TIME_UNTIL_QUERY}ms until fetch wallet balances`
    );
    await sleep(SLEEP_TIME_UNTIL_QUERY);

    const originBalance = await metagraphTokenClient.getBalance();
    const destinationBalance = await metagraphTokenClient.getBalanceFor(
      destination.address
    );

    return { originBalance, destinationBalance };
  } catch (error) {
    const errorMessage = `Error when sending transactions between wallets, message: ${error}`;
    logMessage(errorMessage);
    throw error;
  }
};

const sendDoubleSpendTransaction = async (networkOptions) => {
  const lastRef = await dag4.network.getAddressLastAcceptedTransactionRef(
    FIRST_WALLET_ADDRESS
  );

  const accountFirstNode = dag4.createAccount();
  accountFirstNode.loginSeedPhrase(FIRST_WALLET_SEED_PHRASE);
  await accountFirstNode.connect({
    networkVersion: "2.0",
    l0Url: networkOptions.l0GlobalUrl,
    l1Url: networkOptions.dagL1UrlFirstNode,
    testnet: true,
  });
  const signedTransaction = await accountFirstNode.generateSignedTransaction(
    SECOND_WALLET_ADDRESS,
    5000,
    1,
    lastRef
  );

  const accountSecondNode = dag4.createAccount();
  accountSecondNode.loginSeedPhrase(FIRST_WALLET_SEED_PHRASE);
  await accountSecondNode.connect({
    networkVersion: "2.0",
    l0Url: networkOptions.l0GlobalUrl,
    l1Url: networkOptions.dagL1UrlSecondNode,
    testnet: true,
  });
  const signedTransaction2 = await accountSecondNode.generateSignedTransaction(
    THIRD_WALLET_ADDRESS,
    5000,
    1,
    lastRef
  );

  try {
    await Promise.all([
      dag4.network.postTransaction(signedTransaction),
      dag4.network.postTransaction(signedTransaction2),
    ]);

    logMessage(
      `Waiting ${SLEEP_TIME_UNTIL_QUERY}ms until fetch wallet balances`
    );
    await sleep(SLEEP_TIME_UNTIL_QUERY);

    const firstWalletBalance = await accountFirstNode.getBalanceFor(
      FIRST_WALLET_ADDRESS
    );
    const secondWalletBalance = await accountFirstNode.getBalanceFor(
      SECOND_WALLET_ADDRESS
    );
    const thirdWalletBalance = await accountFirstNode.getBalanceFor(
      THIRD_WALLET_ADDRESS
    );

    if (secondWalletBalance === 9900 && thirdWalletBalance === 5000) {
      logMessage(
        `Amount sent to third wallet ${thirdWalletBalance} and not to second: ${secondWalletBalance}`
      );
      logMessage(`FirstWalletBalance: ${firstWalletBalance}`);
      logMessage(`SecondWalletBalance: ${secondWalletBalance}`);
      logMessage(`ThirdWalletBalance: ${thirdWalletBalance}`);
      return;
    }

    if (secondWalletBalance === 14900 && thirdWalletBalance === 0) {
      logMessage(
        `Amount sent to second wallet ${secondWalletBalance} and not to third: ${thirdWalletBalance}`
      );
      logMessage(`FirstWalletBalance: ${firstWalletBalance}`);
      logMessage(`SecondWalletBalance: ${secondWalletBalance}`);
      logMessage(`ThirdWalletBalance: ${thirdWalletBalance}`);
      return;
    }

    throw Error(`Double spend occurred`);
  } catch (error) {
    const errorMessage = `Error when sending double spend transaction between wallets, message: ${error}`;
    logMessage(errorMessage);
    throw error;
  }
};

const assertBalance = async (originBalance, destinationBalance, isInitial) => {
  const expectedOriginBalance = isInitial ? 8900 : 9900;
  const expectedDestinationBalance = isInitial ? 11000 : 9900;

  if (
    Number(originBalance) !== expectedOriginBalance ||
    Number(destinationBalance) !== expectedDestinationBalance
  ) {
    throw Error(`
        Error sending transactions. Wallet balances are different than expected:
        expectedOriginBalance: ${expectedOriginBalance} ---- originBalance: ${originBalance}
        expectedDestinationBalance: ${expectedDestinationBalance} ---- destinationBalance: ${destinationBalance}
        `);
  }

  logMessage(
    `Origin Balance: expected ${expectedOriginBalance} ---- actual: ${originBalance}`
  );
  logMessage(
    `Destination Balance: expected ${expectedDestinationBalance} ---- actual: ${destinationBalance}`
  );
};

const sendDAGTransactions = async (account1, account2, networkOptions) => {
  try {
    logMessage(
      `Starting batch DAG Transactions from: ${account1.address} to ${account2.address}`
    );
    const { originBalance, destinationBalance } = await handleBatchTransactions(
      account1,
      account2,
      networkOptions
    );

    await assertBalance(originBalance, destinationBalance, true);

    logMessage(
      `Finished batch DAG Transactions from: ${account1.address} to ${account2.address}`
    );
  } catch (error) {
    logMessage(
      `Error sending forth transactions from: ${account1.address} to ${account2.address}:`,
      error
    );
    throw error;
  }

  try {
    logMessage(
      `Starting batch DAG Transactions from: ${account2.address} to ${account1.address}`
    );
    const { originBalance, destinationBalance } = await handleBatchTransactions(
      account2,
      account1,
      networkOptions
    );

    await assertBalance(originBalance, destinationBalance, false);

    logMessage(
      `Finished batch DAG Transactions from: ${account2.address} to ${account1.address}`
    );
  } catch (error) {
    logMessage(
      `Error sending back transactions from: ${account2.address} to ${account1.address}:`,
      error
    );
    throw error;
  }

  try {
    logMessage("Starting to send double spend transaction");
    await sendDoubleSpendTransaction(networkOptions);

    logMessage("Finished double spend transaction");
  } catch (error) {
    logMessage(
      `Error sending back transactions from: ${account2.address} to ${account1.address}:`,
      error
    );
    throw error;
  }
  logMessage("Script finished");
};

const sendCurrencyTransactions = async (account1, account2, networkOptions) => {
  try {
    logMessage(
      `Starting batch METAGRAPH Transactions from: ${account1.address} to ${account2.address}`
    );
    const { originBalance, destinationBalance } =
      await handleMetagraphBatchTransactions(
        account1,
        account2,
        networkOptions
      );

    await assertBalance(originBalance, destinationBalance, true);

    logMessage(
      `Finished batch METAGRAPH Transactions from: ${account1.address} to ${account2.address}`
    );
  } catch (error) {
    logMessage(
      `Error sending forth transactions from: ${account1.address} to ${account2.address}:`,
      error
    );
    throw error;
  }

  try {
    logMessage(
      `Starting batch METAGRAPH Transactions from: ${account2.address} to ${account1.address}`
    );
    const { originBalance, destinationBalance } =
      await handleMetagraphBatchTransactions(
        account2,
        account1,
        networkOptions
      );

    await assertBalance(originBalance, destinationBalance, false);

    logMessage(
      `Finished batch METAGRAPH Transactions from: ${account2.address} to ${account1.address}`
    );
  } catch (error) {
    logMessage(
      `Error sending back transactions from: ${account2.address} to ${account1.address}:`,
      error
    );
    throw error;
  }
};

const sendTransactionsUsingUrls = async (
  {
    metagraphId,
    l0GlobalUrl,
    dagL1UrlFirstNode,
    dagL1UrlSecondNode,
    l0MetagraphUrl,
    l1MetagraphUrl,
  },
  isCurrencyLayer
) => {
  const account1 = dag4.createAccount();
  account1.loginSeedPhrase(FIRST_WALLET_SEED_PHRASE);

  const account2 = dag4.createAccount();
  account2.loginSeedPhrase(SECOND_WALLET_SEED_PHRASE);

  const networkOptions = {
    metagraphId,
    l0GlobalUrl,
    dagL1UrlFirstNode,
    dagL1UrlSecondNode,
    l0MetagraphUrl,
    l1MetagraphUrl,
  };

  if(isCurrencyLayer){
    await sendCurrencyTransactions(account1, account2, networkOptions)
    return
  }

  await sendDAGTransactions(account1, account2, networkOptions)

  return;
};

const sendTransactions = async (isCurrencyLayer) => {
  const metagraphId = "custom_id";
  const l0GlobalUrl = "http://localhost:9000";
  const dagL1UrlFirstNode = "http://localhost:9100";
  const dagL1UrlSecondNode = "http://localhost:9200";
  const l0MetagraphUrl = "http://localhost:9400";
  const l1MetagraphUrl = "http://localhost:9700";

  await sendTransactionsUsingUrls(
    {
      metagraphId,
      l0GlobalUrl,
      dagL1UrlFirstNode,
      dagL1UrlSecondNode,
      l0MetagraphUrl,
      l1MetagraphUrl,
    },
    isCurrencyLayer
  );
};

module.exports = {
  sendTransactions,
};
