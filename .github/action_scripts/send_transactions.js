const { dag4 } = require( '@stardust-collective/dag4' );

const logMessage = ( message ) => {
    const formattedMessage = {
        message
    };
    console.log( formattedMessage );
};

const batchTransaction = async (
    origin,
    destination
) => {
    try {
        const txnsData = [];
        for( let idx = 0; idx < 100; idx++ ) {
            const txnBody = {
                address: destination.address,
                amount: 10,
                fee: 1
            };

            txnsData.push( txnBody );
        }

        logMessage( 'Starting generation' );
        const generatedTransactions = await origin.generateBatchTransactions( txnsData );
        logMessage( 'Generated' );

        logMessage( 'Starting sending' );
        const hashes = await origin.sendBatchTransactions( generatedTransactions );
        logMessage( 'Sent' );

        logMessage(
            `Transaction from: ${
                origin.address
            } sent - batch. Generated transaction response body: ${JSON.stringify(
                generatedTransactions
            )}. Post hashes: ${hashes}`
        );

        return hashes;
    } catch( e ) {
        throw Error( `Error when sending batch transaction: ${e}` );
    }
};

const batchMetagraphTransaction = async (
    metagraphTokenClient,
    origin,
    destination
) => {
    try {
        const txnsData = [];
        for( let idx = 0; idx < 100; idx++ ) {
            const txnBody = {
                address: destination.address,
                amount: 50,
                fee: 1
            };

            txnsData.push( txnBody );
        }

        logMessage( 'Starting generation' );
        const generatedTransactions =
      await metagraphTokenClient.generateBatchTransactions( txnsData );

        logMessage( 'Generated' );
        logMessage( generatedTransactions );
        logMessage( 'Starting sending' );
        const hashes = await metagraphTokenClient.sendBatchTransactions(
            generatedTransactions
        );
        logMessage( 'Sent' );

        logMessage(
            `Transaction from: ${
                origin
            } sent - batch. Generated transaction response body: ${JSON.stringify(
                generatedTransactions
            )}. Post hashes: ${hashes}`
        );

        return hashes;
    } catch( e ) {
        throw Error( `Error when sending batch transaction: ${e}` );
    }
};

const handleBatchTransactions = async ( origin, destination, networkOptions ) => {
    await origin.connect(
        {
            networkVersion: '2.0',
            l0Url: networkOptions.l0GlobalUrl,
            l1Url: networkOptions.dagL1Url,
            testnet: true
        }
    );

    try {
        await batchTransaction(
            origin,
            destination
        );

        const originBalance = origin.getBalance();
        const destinationBalance = origin.getBalanceFor( destination.address );
        logMessage( `Origin Balance (DAG): ${originBalance}` );
        logMessage( `Destination Balance (DAG): ${destinationBalance}` );

        return;
    } catch( error ) {
        const errorMessage = `Error when sending transactions between wallets, message: ${error}`;
        logMessage( errorMessage );
        throw error;
    }
};

const handleMetagraphBatchTransactions = async ( origin, destination, networkOptions ) => {
    await origin.connect(
        {
            networkVersion: '2.0',
            l0Url: networkOptions.l0GlobalUrl,
            l1Url: networkOptions.dagL1Url,
            testnet: true
        }
    );

    try {
        const metagraphTokenClient = origin.createMetagraphTokenClient( {
            id: networkOptions.metagraphId,
            l0Url: networkOptions.l0MetagraphUrl,
            l1Url: networkOptions.l1MetagraphUrl,
            testnet: true
        } );

        await batchMetagraphTransaction(
            metagraphTokenClient,
            origin,
            destination
        );

        const originBalance = metagraphTokenClient.getBalance();
        const destinationBalance = metagraphTokenClient.getBalanceFor( destination.address );
        logMessage( `Origin Balance (Metagraph): ${originBalance}` );
        logMessage( `Destination Balance (Metagraph): ${destinationBalance}` );

        return;
    } catch( error ) {
        const errorMessage = `Error when sending transactions between wallets, message: ${error}`;
        logMessage( errorMessage );
        throw error;
    }
};

const sendTransactionsUsingUrls = async (
    metagraphId,
    l0GlobalUrl,
    dagL1Url,
    l0MetagraphUrl,
    l1MetagraphUrl
) => {
    //DAG4Zd2W2JxL1f1gsHQCoaKrRonPSSHLgcqD7osU
    const account1 = dag4.createAccount();
    account1.loginSeedPhrase(
        'drift doll absurd cost upon magic plate often actor decade obscure smooth'
    );

    //DAG6kfTqFxLLPLopHqR43CeQrcvJ5k3eXgYSeELt
    const account2 = dag4.createAccount();
    account2.loginSeedPhrase(
        'upper pistol movie hedgehog case exhaust wife injury joke live festival shield'
    );

    const networkOptions = {
        metagraphId,
        l0GlobalUrl,
        dagL1Url,
        l0MetagraphUrl,
        l1MetagraphUrl
    };

    try {
        logMessage( `Starting batch DAG Transactions from: ${account1.address} to ${account2.address}` );
        await handleBatchTransactions( account1, account2, networkOptions );
        logMessage( `Finished batch DAG Transactions from: ${account1.address} to ${account2.address}` );
    } catch( error ) {
        logMessage( `Error sending forth transactions from: ${account1.address} to ${account2.address}:`, error );
        throw error;
    }

    try {
        logMessage( `Starting batch DAG Transactions from: ${account2.address} to ${account1.address}` );
        await handleBatchTransactions( account2, account1, networkOptions );
        logMessage( `Finished batch DAG Transactions from: ${account2.address} to ${account1.address}` );
    } catch( error ) {
        logMessage( `Error sending back transactions from: ${account2.address} to ${account1.address}:`, error );
        throw error;
    }

    try {
        logMessage( `Starting batch METAGRAPH Transactions from: ${account1.address} to ${account2.address}` );
        await handleMetagraphBatchTransactions( account1, account2, networkOptions );
        logMessage( `Finished batch METAGRAPH Transactions from: ${account1.address} to ${account2.address}` );
    } catch( error ) {
        logMessage( `Error sending forth transactions from: ${account1.address} to ${account2.address}:`, error );
        throw error;
    }

    try {
        logMessage( `Starting batch METAGRAPH Transactions from: ${account2.address} to ${account1.address}` );
        await handleMetagraphBatchTransactions( account2, account1, networkOptions );
        logMessage( `Finished batch METAGRAPH Transactions from: ${account2.address} to ${account1.address}` );
    } catch( error ) {
        logMessage( `Error sending back transactions from: ${account2.address} to ${account1.address}:`, error );
        throw error;
    }

    logMessage( 'Script finished' );
    return;
};

const sendTransactions = async () => {
    const metagraphId = 'custom_id';
    const l0GlobalUrl =
    'http://localhost:9000';
    const dagL1Url =
    'http://localhost:9100';
    const l0MetagraphUrl =
    'http://localhost:9400';
    const l1MetagraphUrl =
    'http://localhost:9700';

    await sendTransactionsUsingUrls(
        metagraphId,
        l0GlobalUrl,
        dagL1Url,
        l0MetagraphUrl,
        l1MetagraphUrl
    );
};

sendTransactions();