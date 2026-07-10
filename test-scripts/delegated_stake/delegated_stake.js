import axios from 'axios';
import pkg from '@stardust-collective/dag4';
const { dag4 } = pkg;
import EC from 'elliptic';
import { createRequire } from 'module';

const require = createRequire(import.meta.url);
const {
    checkOk,
    checkBadRequest,
    dagToDatum,
    getPrivateKeyAndNodeIdFromFile,
    postNodeParamsNodeId,
    getAccountDelegatedStakes,
    assertDelegatedStakes,
    fetchStakeWithRewardsBalance,
    createTokenLock,
    assertBalanceChange,
    getNodeParams,
    fetchSnapshot,
    assertRewardTxnInSnapshot,
    assertTokenUnlockInSnapshot,
  } = require('../../.github/action_scripts/delegated_staking/lib.js');
    

// Initialize elliptic curve for key generation
const ec = new EC.ec('secp256k1');

/**
 * Extract tokenLocks from JSON data using path '.[1].activeTokenLocks'
 * @param {Object|string|Array} jsonData - JSON data (can be parsed object, JSON string, or array)
 * @returns {Array} Array of active token locks
 */
function getActiveTokenLocksTransactions(jsonData, accountAddress) {
    try {
        // Parse if it's a string, otherwise use as-is
        const data = typeof jsonData === 'string' ? JSON.parse(jsonData) : jsonData;

        
        // Extract tokenLocks using path '.[1].activeTokenLocks'
        // This means: root[1].activeTokenLocks
        if (Array.isArray(data) && data.length > 1) {
            const tokenLocks = data[1]?.activeTokenLocks?.[accountAddress];
            return tokenLocks || [];
        }
        
        return [];
    } catch (error) {
        console.error('Error parsing JSON or extracting token locks:', error.message);
        throw error;
    }
}

async function getActiveTokenLocks(account, urls) {
    const uri = `${urls.globalL0Url}/token-locks/${account.address}?t=${Date.now()}`;
    console.log(`Fetching token locks from ${uri}`);
    const response = await axios.get(
        uri,
        {
          headers: {
            'Cache-Control': 'no-cache, no-store, must-revalidate',
            Pragma: 'no-cache',
            Expires: '0',
          },
        },
      )
      return response.data  
}  

/**
 * Compute hash of token lock value using dag4js library
 * @param {Object} lockValue - The lock value object to hash
 * @returns {Promise<string>} The computed hash
 */
async function computeHash(value) {
    // Compute hash of lock.value using dag4js library
    const { compressed } = await dag4.keyStore.brotliCompress(value);
    // Convert Uint8Array to Buffer for sha256
    const compressedBuffer = Buffer.from(compressed);
    const computedHash = dag4.keyStore.sha256(compressedBuffer);
    return computedHash;
}

/**
 * Download combined snapshot from the global L0 API
 * @param {Object} urls - Network URLs object with globalL0Url
 * @returns {Promise<Object>} The combined snapshot data
 */
async function downloadCombinedSnapshot(urls) {
    console.log('\n📥 Downloading combined snapshot...');
    const response = await axios.get(`${urls.globalL0Url}/global-snapshots/latest/combined`);
    return response.data;
}

/**
 * Extract account from hex-encoded private key
 */
function extractKeysAndAccount(privateKeyHex) {
    const privateKeyBuffer = Buffer.from(privateKeyHex, 'hex');

    try {
        const privateKeyString = privateKeyHex;
        const keyPair = ec.keyFromPrivate(privateKeyBuffer);
    
        const uncompressedPublicKey = keyPair.getPublic(false, 'hex'); // Uncompressed format
        const nodeId = uncompressedPublicKey.slice(2); // Remove the '0x04' prefix
    
        const account = dag4.createAccount(privateKeyString);
    
        return { privateKeyString, nodeId, account };
    } catch (error) {
        console.error('Error processing the private key:', error);
        throw error;
    }
}

/**
 * Create and post node parameters using postNodeParamsNodeId from DAG4js
 * @param {string} privateKeyHex - Hex-encoded private key
 * @param {Object} urls - Network URLs object with globalL0Url
 * @param {string} parametersName - Name/description for the node parameters
 * @param {number} rewardFraction - Reward fraction for delegated stakes
 * @returns {Promise<Object>} Response from posting node parameters
 */
async function createNodeParams(privateKeyHex, urls, parametersName, rewardFraction) {
    const { nodeId, account, privateKeyString } = extractKeysAndAccount(privateKeyHex);
    
    console.log(`\n🖥️  Creating node parameters...`);
    console.log(`Node ID: ${nodeId}`);
    console.log(`Parameters Name: ${parametersName}`);
    console.log(`Reward Fraction: ${rewardFraction}`);
    
    const response = await postNodeParamsNodeId(
        urls,
        nodeId,
        account,
        privateKeyString,
        parametersName,
        rewardFraction
    );
    
    if (response && response.status === 200) {
        console.log(`✅ Node parameters created successfully!`);
        console.log(`Response status: ${response.status}`);
    } else {
        // console.log(`Response: ${JSON.stringify(response, null, 2)}`);
        console.log(`⚠️  Node parameters response: ${response.statusText || 'unknown'}`);
    }
    
    return {
        nodeId,
        account,
        address: account.address,
        privateKeyString,
        response
    };
}

/**
 * Connect to network and login to account
 * @param {string} privateKeyHex - Hex-encoded private key
 * @param {Object} options - Optional configuration
 * @param {string} options.network - 'testnet', 'integrationnet', or 'mainnet' (default: 'testnet')
 * @param {string} options.ip - IP address for custom node (optional)
 * @param {boolean} options.includeUrls - Whether to include network URLs in return (default: false)
 * @returns {Promise<Object>} Object containing account, address, and optionally urls
 */
async function connectAndLogin(privateKeyHex, options = {}) {
    // Extract account from private key
    const { account } = extractKeysAndAccount(privateKeyHex);
    const address = account.address;
    
    // Configure network
    const network = options.network || 'testnet';
    const isTestnet = network === 'testnet' || network === 'integrationnet';
    
    // Connect to network
    if (network === 'dev') {
        dag4.account.connect({
            networkVersion: '2.0',
            testnet: isTestnet,
            l0Url: `http://localhost:9000`,
            l1Url: `http://localhost:9100`,
        });
    } else if (options.ip) {
        dag4.account.connect({
            networkVersion: '2.0',
            testnet: isTestnet,
            l0Url: `http://${options.ip}:9000`,
            l1Url: `http://${options.ip}:9100`,
        });
    } else {
        dag4.account.connect({
            networkVersion: '2.0',
            testnet: isTestnet,
        });
    }
    
    // Login to account
    await dag4.account.loginPrivateKey(privateKeyHex);
    
    const result = { account, address };
    
    // Optionally include network URLs
    if (options.includeUrls) {
        const networkInfo = dag4.network.getNetwork();
        result.urls = {
            globalL0Url: networkInfo.l0Url,
            dagL1Url: networkInfo.l1Url
        };
    }
    
    return result;
}

/**
 * Create a token lock for a specified amount
 * @param {Object} account - DAG account object (from connectAndLogin)
 * @param {Object} urls - Network URLs object with dagL1Url
 * @param {number} amount - Amount in DAG to lock
 * @param {Object} options - Optional configuration
 * @param {number} options.unlockEpoch - Epoch when the lock should unlock (optional, null for no unlock)
 * @param {string} options.replaceTokenLockRef - Hash of existing token lock to replace (optional)
 * @param {number} options.fee - Transaction fee (default: 0)
 * @returns {Promise<Object>} Transaction result with hash
 */
async function createTokenLockForAmount(account, urls, amount, options = {}) {
    try {
        const sourceAddress = account.address;
        
        console.log(`Source address: ${sourceAddress}`);
        console.log(`Amount to lock: ${amount} DAG`);
        
        // Check balance before lock
        const balance = await dag4.network.getAddressBalance(sourceAddress);
        console.log(`Current balance: ${balance.balance / 100000000} DAG`);
        
        // Convert amount to datum (multiply by 100000000)
        const lockAmountDatum = Math.round(amount * 100000000);
        
        // Create token lock
        console.log('\n🔒 Creating token lock...');
        const result = await account.postTokenLock({
            source: sourceAddress,
            amount: lockAmountDatum,
            tokenL1Url: urls.dagL1Url,
            unlockEpoch: options.unlockEpoch ?? null,
            currencyId: null,
            replaceTokenLockRef: options.replaceTokenLockRef ?? null,
            fee: options.fee ?? 0,
        });
        console.log(`Token lock: ${JSON.stringify(result, null, 2)}`);
        
        if (!result || !result.hash) {
            throw new Error('Failed to create token lock - no hash returned');
        }
        
        console.log(`✅ Token lock created successfully!`);
        console.log(`Transaction hash: ${result.hash}`);
        
        return {
            success: true,
            transactionHash: result.hash,
            address: sourceAddress,
            amount: amount,
            amountDatum: lockAmountDatum,
            unlockEpoch: options.unlockEpoch ?? null
        };
        
    } catch (error) {
        console.error('❌ Token lock creation failed:', error.message);
        throw error;
    }
}

/**
 * Create a delegated stake
 * @param {Object} account - DAG account object (from connectAndLogin)
 * @param {string} tokenLockHash - Hash of the token lock to use for staking
 * @param {number} amount - Amount in DAG to stake (should match token lock amount)
 * @param {string} nodeId - Node ID to stake to
 * @param {Object} options - Optional configuration
 * @param {number} options.fee - Transaction fee (default: 0)
 * @returns {Promise<Object>} Transaction result with hash
 */
async function createDelegatedStake(account, tokenLockHash, amount, nodeId, options = {}) {
    try {
        const sourceAddress = account.address;
        
        console.log(`Source address: ${sourceAddress}`);
        console.log(`Token lock hash: ${tokenLockHash}`);
        console.log(`Amount to stake: ${amount} DAG`);
        console.log(`Node ID: ${nodeId}`);
        
        // Convert amount to datum (multiply by 100000000)
        const stakeAmountDatum = Math.round(amount * 100000000);
        
        // Create delegated stake
        console.log('\n📊 Creating delegated stake...');
        const result = await account.postDelegatedStake({
            source: sourceAddress,
            nodeId: nodeId,
            amount: stakeAmountDatum,
            fee: options.fee || 0,
            tokenLockRef: tokenLockHash,
        });
        
        if (!result || !result.hash) {
            throw new Error('Failed to create delegated stake - no hash returned');
        }
        
        console.log(`✅ Delegated stake created successfully!`);
        console.log(`Transaction hash: ${result.hash}`);
        
        return {
            success: true,
            transactionHash: result.hash,
            address: sourceAddress,
            tokenLockHash: tokenLockHash,
            amount: amount,
            amountDatum: stakeAmountDatum,
            nodeId: nodeId
        };
        
    } catch (error) {
        console.error('❌ Delegated stake creation failed:', error.message);
        throw error;
    }
}

/**
 * Withdraw a delegated stake
 * @param {Object} account - DAG account object (from connectAndLogin)
 * @param {string} stakeHash - Hash of the delegated stake to withdraw
 * @returns {Promise<Object>} Transaction result with hash
 */
async function withdrawDelegatedStake(account, stakeHash) {
    try {
        const sourceAddress = account.address;
        
        console.log(`Source address: ${sourceAddress}`);
        console.log(`Stake hash: ${stakeHash}`);
        
        // Withdraw delegated stake
        console.log('\n📤 Withdrawing delegated stake...');
        const result = await account.putWithdrawDelegatedStake({
            source: sourceAddress,
            stakeRef: stakeHash,
        });
        
        if (!result || !result.hash) {
            throw new Error('Failed to withdraw delegated stake - no hash returned');
        }
        
        console.log(`✅ Delegated stake withdrawal initiated successfully!`);
        console.log(`Transaction hash: ${result.hash}`);
        
        return {
            success: true,
            transactionHash: result.hash,
            address: sourceAddress,
            stakeHash: stakeHash
        };
        
    } catch (error) {
        console.error('❌ Delegated stake withdrawal failed:', error.message);
        throw error;
    }
}

/**
 * Simple DAG transfer function
 * @param {Object} account - DAG account object (from connectAndLogin)
 * @param {string} destinationAddress - DAG address to send to
 * @param {number} amount - Amount in DAG to transfer
 * @returns {Promise<Object>} Transaction result with hash
 */
async function transferDag(account, destinationAddress, amount) {
    try {
        const sourceAddress = account.address;
        
        console.log(`Source address: ${sourceAddress}`);
        console.log(`Destination address: ${destinationAddress}`);
        console.log(`Amount: ${amount} DAG`);
        
        // Check balance before transfer
        const balance = await dag4.network.getAddressBalance(sourceAddress);
        console.log(`Current balance: ${balance.balance / 100000000} DAG`);
        
        // Perform transfer
        console.log('\n💸 Sending transaction...');
        const result = await dag4.account.transferDag(destinationAddress, amount);
        
        console.log(`✅ Transaction successful!`);
        console.log(`Transaction hash: ${result.hash || result.txHash}`);
        
        return {
            success: true,
            transactionHash: result.hash || result.txHash,
            from: sourceAddress,
            to: destinationAddress,
            amount: amount
        };
        
    } catch (error) {
        console.error('❌ Transfer failed:', error.message);
        throw error;
    }
}

/**
 * Parse named command line arguments
 * @returns {Object} Parsed arguments
 */
function parseArgs() {
    const args = {};
    for (let i = 2; i < process.argv.length; i++) {
        const arg = process.argv[i];
        if (arg.startsWith('--')) {
            const key = arg.slice(2);
            const value = process.argv[i + 1];
            if (value && !value.startsWith('--')) {
                args[key] = value;
                i++; // Skip the next argument as it's the value
            } else {
                args[key] = true; // Flag without value
            }
        }
    }
    return args;
}

// Example usage
async function main() {
    // Get parameters from named command line arguments
    const args = parseArgs();
    const privateKeyHex = args.key || args.privateKey || process.env.DAG_PRIVATE_KEY;
    const amount = args.amount ? parseFloat(args.amount) : undefined;
    const tokenLockHash = args.tokenLockHash;
    const nodeId = args.nodeId;
    const stakeHash = args.stakeHash;
    const network = args.network || 'testnet';
    const ip = args.ip;
    const command = args.command;
    const replaceTokenLockRef = args.replaceTokenLockRef;
    const parametersName = args.parametersName;
    const rewardFraction = args.rewardFraction;

    if (!privateKeyHex) {
        console.log('Usage: node delegated_stake.js --key <privateKeyHex> [--amount <amount>] [--network <network>] [--ip <ip>]');
        console.log('       DAG_PRIVATE_KEY=<hex> node delegated_stake.js [options]');
        console.log('Options:');
        console.log('  --key, --privateKey    Private key in hex format (or set DAG_PRIVATE_KEY env var)');
        console.log('  --network              Network: testnet, integrationnet, mainnet (default: testnet)');
        console.log('  --ip                   Custom node IP address (optional)');
        console.log('  --command              Command: createTokenLock, replaceTokenLock, listTokenLocks, createDelegatedStake, withdrawDelegatedStake, smokeTest (optional)');
        console.log('  --amount               Amount in DAG (optional)');
        console.log('  --replaceTokenLockRef  Replace token lock reference in hex format (optional)');  
        console.log('  --tokenLockHash        Token lock hash in hex format (optional)');
        console.log('  --nodeId               Node ID in hex format (optional)');
        console.log('  --stakeHash            Stake hash in hex format (optional)');
        console.log('  --parametersName       Parameters name (optional)');
        console.log('  --rewardFraction       Reward fraction (optional)');
        console.log('\nExamples:');
        console.log('  node delegated_stake.js --key <privateKey> --amount 1.0 --network integrationnet');
        console.log('  node delegated_stake.js --key <privateKey> --command smokeTest --amount 6000 --network testnet');
        console.log('  node delegated_stake.js --key <privateKey> --command smokeTest --amount max --network testnet');
        process.exit(1);
    }
    
    try {
        // Connect to network and login
        const { account, address, urls } = await connectAndLogin(privateKeyHex, {
            network,
            ip,
            includeUrls: true
        });

        if (command === 'createTokenLock') {
            if (!amount) {
                console.log('--amount is required');
                process.exit(1);
            }
            await createTokenLockForAmount(account, urls, amount);
        } else if (command === 'replaceTokenLock') {
            if (!amount) {
                console.log('--amount is required');
                process.exit(1);
            }
            if (!replaceTokenLockRef) {
                console.log('--replaceTokenLockRef is required');
                process.exit(1);
            }
            await createTokenLockForAmount(account, urls, amount, {replaceTokenLockRef: replaceTokenLockRef});
        } else if (command === 'listTokenLocks') {
            // Get token locks
            console.log('\n🔒 Fetching token locks...');
            
            const tokenLocks = await getActiveTokenLocks(account, urls);
            if (tokenLocks && tokenLocks.length > 0) {
                console.log(`Found ${tokenLocks.length} active token lock(s):`);
                for (let index = 0; index < tokenLocks.length; index++) {
                    const lock = tokenLocks[index];
                    const computedHash = await computeHash(lock);

                    console.log(`  ${index + 1}. Hash: ${computedHash}`);
                    console.log(`     Amount: ${lock.amount / 100000000} DAG`);
                    console.log(`     Source: ${lock.source}`);
                    console.log(`     Replacement Hash: ${lock.replaceTokenLockRef}`);
                    if (lock.unlockEpoch) {
                        console.log(`     Unlock Epoch: ${lock.unlockEpoch}`);
                    }
                };
            } else {
                console.log('No active token locks found.');
            }

        } else if (command === 'createDelegatedStake') {
            if (!tokenLockHash) {
                console.log('--tokenLockHash is required');
                process.exit(1);
            }
            if (!amount) {
                console.log('--amount is required');
                process.exit(1);
            }
            if (!nodeId) {
                console.log('--nodeId is required');
                process.exit(1);
            }
            await createDelegatedStake(account, tokenLockHash, amount, nodeId);
        } else if (command === 'withdrawDelegatedStake') {
            if (!stakeHash) {   
                console.log('--stakeHash is required');
                process.exit(1);
            }
            await withdrawDelegatedStake(account, stakeHash);
        } else if (command === 'createNodeParams') {
            if (!parametersName) {
                console.log('--parametersName is required');
                process.exit(1);
            }
            if (!rewardFraction) {
                console.log('--rewardFraction is required');
                process.exit(1);
            }
            await createNodeParams(privateKeyHex, urls, parametersName, rewardFraction);
        } else if (command === 'smokeTest') {
            // Handle smokeTest command
            const amountArg = args.amount;
            if (!amountArg) {
                console.log('--amount is required (number > 5000 or "max")');
                process.exit(1);
            }

            let smokeTestAmount;
            if (amountArg === 'max') {
                // Get current balance
                const balance = await dag4.network.getAddressBalance(address);
                smokeTestAmount = balance.balance / 100000000;
                console.log(`Using max balance: ${smokeTestAmount} DAG`);
            } else {
                smokeTestAmount = parseFloat(amountArg);
                if (isNaN(smokeTestAmount)) {
                    console.log('--amount must be a number or "max"');
                    process.exit(1);
                }
            }

            if (smokeTestAmount <= 5000) {
                console.log(`Error: amount must be strictly greater than 5000 DAG (got ${smokeTestAmount})`);
                process.exit(1);
            }

            await smokeTest(privateKeyHex, account, urls, smokeTestAmount);
        } else {
        
        console.log(`\n📍 DAG Address: ${address}`);
        
        // Get account balance
        console.log('\n💰 Fetching account balance...');
        const balance = await dag4.network.getAddressBalance(address);
        console.log(`Balance: ${balance.balance / 100000000} DAG`);
        
        // Get delegated stakes
        console.log('\n📊 Fetching delegated stakes...');
        const stakeResponse = await getAccountDelegatedStakes(urls, address);
        
        if (stakeResponse.activeDelegatedStakes && stakeResponse.activeDelegatedStakes.length > 0) {
            console.log(`Found ${stakeResponse.activeDelegatedStakes.length} active delegated stake(s):`);
            stakeResponse.activeDelegatedStakes.forEach((stake, index) => {
                console.log(`  ${index + 1}. Hash: ${stake.hash}`);
                console.log(`     Amount: ${stake.amount / 100000000} DAG`);
                console.log(`     Node ID: ${stake.nodeId}`);
                console.log(`     Token Lock Hash: ${stake.tokenLockRef}`);
                if (stake.rewardAmount) {
                    console.log(`     Reward Amount: ${stake.rewardAmount / 100000000} DAG`);
                }
            });
        } else {
            console.log('No active delegated stakes found.');
        }
        
        if (stakeResponse.pendingWithdrawals && stakeResponse.pendingWithdrawals.length > 0) {
            console.log(`\nFound ${stakeResponse.pendingWithdrawals.length} pending withdrawal(s):`);
            stakeResponse.pendingWithdrawals.forEach((withdrawal, index) => {
                console.log(`  ${index + 1}. Hash: ${withdrawal.hash}`);
                console.log(`     Amount: ${withdrawal.amount / 100000000} DAG`);
                console.log(`     Node ID: ${withdrawal.nodeId}`);
                console.log(`     Token Lock Hash: ${withdrawal.tokenLockRef}`);
                if (withdrawal.rewardAmount) {
                    console.log(`     Reward Amount: ${withdrawal.rewardAmount / 100000000} DAG`);
                }
            });
        }

        //   Print the list of node parameters (nodeId and rewardFraction only)
        console.log('\n🖥️  Fetching node parameters...');
        try {
            const nodeParams = await getNodeParams(urls);
            
            // Handle different response structures
            const nodes = Array.isArray(nodeParams) ? nodeParams : (nodeParams.nodes || []);
            
            if (nodes && nodes.length > 0) {
                console.log(`Found ${nodes.length} node(s) with parameters:`);
                nodes.forEach((node, index) => {
                    const nodeId = node.nodeId || node.peerId || 'N/A';
                    const rewardFraction = node.delegatedStakeRewardParameters?.rewardFraction ?? 
                                         node.rewardFraction ?? 
                                         'N/A';
                    
                    console.log(`  ${index + 1}. Node ID: ${nodeId}`);
                    console.log(`     Reward Fraction: ${rewardFraction}`);
                });
            } else {
                console.log('No node parameters found.');
            }
        } catch (error) {
            console.error('Failed to fetch node parameters:', error.message);
        }
    }
    } catch (error) {
        console.error('Failed:', error);
        process.exit(1);
    }
}

// Run if executed directly
if (import.meta.url === `file://${process.argv[1]}`) {
    main().catch(console.error);
}

/**
 * Sleep helper function
 * @param {number} ms - Milliseconds to sleep
 */
function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

/**
 * Poll for a token lock with specific hash to appear
 * @param {Object} account - DAG account object
 * @param {Object} urls - Network URLs
 * @param {string} expectedHash - Hash of the token lock to find
 * @param {number} maxAttempts - Maximum polling attempts (default: 30)
 * @param {number} intervalMs - Polling interval in ms (default: 2000)
 * @returns {Promise<Object>} The found token lock
 */
async function pollForTokenLock(account, urls, expectedHash, maxAttempts = 30, intervalMs = 2000) {
    console.log(`\n⏳ Waiting for token lock ${expectedHash} to appear...`);
    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
        const tokenLocks = await getActiveTokenLocks(account, urls);
        for (const lock of tokenLocks) {
            const hash = await computeHash(lock);
            if (hash === expectedHash) {
                console.log(`✅ Token lock found after ${attempt} attempt(s)`);
                return lock;
            }
        }
        console.log(`   Attempt ${attempt}/${maxAttempts} - not found yet...`);
        await sleep(intervalMs);
    }
    throw new Error(`Token lock ${expectedHash} not found after ${maxAttempts} attempts`);
}

/**
 * Poll for delegated stake to appear or update
 * @param {Object} urls - Network URLs
 * @param {string} address - Account address
 * @param {Object} criteria - Criteria to match { tokenLockRef?, nodeId?, amount? }
 * @param {number} maxAttempts - Maximum polling attempts (default: 30)
 * @param {number} intervalMs - Polling interval in ms (default: 2000)
 * @returns {Promise<Object>} The found delegated stake
 */
async function pollForDelegatedStake(urls, address, criteria, maxAttempts = 30, intervalMs = 2000) {
    console.log(`\n⏳ Waiting for delegated stake to appear/update...`);
    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
        const stakeResponse = await getAccountDelegatedStakes(urls, address);
        const stakes = stakeResponse.activeDelegatedStakes || [];

        for (const stake of stakes) {
            let matches = true;
            if (criteria.tokenLockRef && stake.tokenLockRef !== criteria.tokenLockRef) matches = false;
            if (criteria.nodeId && stake.nodeId !== criteria.nodeId) matches = false;
            if (criteria.amount !== undefined && stake.amount !== criteria.amount) matches = false;

            if (matches) {
                console.log(`✅ Delegated stake found after ${attempt} attempt(s)`);
                return stake;
            }
        }
        console.log(`   Attempt ${attempt}/${maxAttempts} - not found yet...`);
        await sleep(intervalMs);
    }
    throw new Error(`Delegated stake matching criteria not found after ${maxAttempts} attempts`);
}

/**
 * Poll for pending withdrawal to appear
 * @param {Object} urls - Network URLs
 * @param {string} address - Account address
 * @param {string} stakeRef - Original stake hash reference
 * @param {number} maxAttempts - Maximum polling attempts (default: 30)
 * @param {number} intervalMs - Polling interval in ms (default: 2000)
 * @returns {Promise<Object>} The found pending withdrawal
 */
async function pollForPendingWithdrawal(urls, address, stakeRef, maxAttempts = 30, intervalMs = 2000) {
    console.log(`\n⏳ Waiting for pending withdrawal to appear...`);
    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
        const stakeResponse = await getAccountDelegatedStakes(urls, address);
        const withdrawals = stakeResponse.pendingWithdrawals || [];

        for (const withdrawal of withdrawals) {
            if (withdrawal.hash === stakeRef || withdrawal.stakeRef === stakeRef) {
                console.log(`✅ Pending withdrawal found after ${attempt} attempt(s)`);
                return withdrawal;
            }
        }
        console.log(`   Attempt ${attempt}/${maxAttempts} - not found yet...`);
        await sleep(intervalMs);
    }
    throw new Error(`Pending withdrawal for stake ${stakeRef} not found after ${maxAttempts} attempts`);
}

/**
 * Poll for node parameters to appear for a specific node ID
 * @param {Object} urls - Network URLs
 * @param {string} nodeId - Node ID to find
 * @param {number} maxAttempts - Maximum polling attempts (default: 30)
 * @param {number} intervalMs - Polling interval in ms (default: 2000)
 * @returns {Promise<Object>} The found node parameters
 */
async function pollForNodeParams(urls, nodeId, maxAttempts = 30, intervalMs = 2000) {
    console.log(`\n⏳ Waiting for node parameters to appear...`);
    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
            const nodeParams = await getNodeParams(urls);
            const nodes = Array.isArray(nodeParams) ? nodeParams : (nodeParams.nodes || []);

            for (const node of nodes) {
                const foundNodeId = node.nodeId || node.peerId;
                if (foundNodeId === nodeId) {
                    console.log(`✅ Node parameters found after ${attempt} attempt(s)`);
                    return node;
                }
            }
        } catch (error) {
            // Ignore errors during polling
        }
        console.log(`   Attempt ${attempt}/${maxAttempts} - not found yet...`);
        await sleep(intervalMs);
    }
    throw new Error(`Node parameters for ${nodeId} not found after ${maxAttempts} attempts`);
}

/**
 * Smoke test for delegated staking workflow
 * @param {string} privateKeyHex - Hex-encoded private key
 * @param {Object} account - DAG account object
 * @param {Object} urls - Network URLs
 * @param {number} amount - Total amount for the test (must be > 5000)
 */
async function smokeTest(privateKeyHex, account, urls, amount) {
    const address = account.address;
    console.log('\n🚀 Starting Smoke Test');
    console.log('='.repeat(50));
    console.log(`Address: ${address}`);
    console.log(`Total amount for test: ${amount} DAG`);
    console.log('='.repeat(50));

    // Step 1: Get first existing node parameters
    console.log('\n📋 STEP 1: Get first existing node parameters');
    console.log('-'.repeat(50));

    let nodeId;
    const nodeParams = await getNodeParams(urls);
    const nodes = Array.isArray(nodeParams) ? nodeParams : (nodeParams.nodes || []);

    if (nodes && nodes.length > 0) {
        nodeId = nodes[0].nodeId || nodes[0].peerId;
        console.log(`✅ Found existing node parameters`);
        console.log(`   Using Node ID: ${nodeId}`);
    } else {
        throw new Error('No node parameters found. Please register node parameters first.');
    }

    // Step 1b: Clean up any existing stakes to this node
    console.log('\n📋 STEP 1b: Check and clean up existing stakes');
    console.log('-'.repeat(50));

    const existingStakes = await getAccountDelegatedStakes(urls, address);
    const stakesToNode = (existingStakes.activeDelegatedStakes || []).filter(s => s.nodeId === nodeId);

    if (stakesToNode.length > 0) {
        console.log(`⚠️  Found ${stakesToNode.length} existing stake(s) to this node, withdrawing...`);
        for (const stake of stakesToNode) {
            console.log(`   Withdrawing stake: ${stake.hash}`);
            await withdrawDelegatedStake(account, stake.hash);
        }
        // Wait for withdrawals to process
        console.log('   Waiting for withdrawals to process...');
        await sleep(5000);
        console.log(`✅ Existing stakes withdrawn`);
    } else {
        console.log(`✅ No existing stakes to clean up`);
    }

    // Step 2: Create token lock for 5000 DAG
    console.log('\n📋 STEP 2: Create token lock for 5000 DAG');
    console.log('-'.repeat(50));

    const initialLockAmount = 5000;
    const tokenLockResult = await createTokenLockForAmount(account, urls, initialLockAmount);
    const initialTokenLockHash = tokenLockResult.transactionHash;

    // Verify token lock is created
    console.log('\n📋 STEP 2b: Verify token lock is created');
    console.log('-'.repeat(50));

    const verifiedLock = await pollForTokenLock(account, urls, initialTokenLockHash);
    console.log(`   Verified amount: ${verifiedLock.amount / 100000000} DAG`);
    if (verifiedLock.amount !== initialLockAmount * 100000000) {
        throw new Error(`Token lock amount mismatch: expected ${initialLockAmount * 100000000}, got ${verifiedLock.amount}`);
    }
    console.log(`✅ Token lock verified successfully`);

    // Step 3: Create delegated stake
    console.log('\n📋 STEP 3: Create delegated stake using token lock');
    console.log('-'.repeat(50));

    const delegatedStakeResult = await createDelegatedStake(
        account,
        initialTokenLockHash,
        initialLockAmount,
        nodeId
    );
    const delegatedStakeHash = delegatedStakeResult.transactionHash;
    console.log(`   Transaction hash: ${delegatedStakeHash}`);

    // Verify delegated stake is created
    console.log('\n📋 STEP 3b: Verify delegated stake is created');
    console.log('-'.repeat(50));

    const verifiedStake = await pollForDelegatedStake(urls, address, {
        tokenLockRef: initialTokenLockHash,
        nodeId: nodeId,
        amount: initialLockAmount * 100000000
    });
    console.log(`   Verified stake hash: ${verifiedStake.hash}`);
    console.log(`   Verified amount: ${verifiedStake.amount / 100000000} DAG`);
    console.log(`   Verified node ID: ${verifiedStake.nodeId}`);
    console.log(`   Verified token lock ref: ${verifiedStake.tokenLockRef}`);
    console.log(`✅ Delegated stake verified successfully`);

    // Step 4: Replace token lock with full amount
    console.log('\n📋 STEP 4: Replace token lock with full amount');
    console.log('-'.repeat(50));

    // Small delay to ensure state is settled
    console.log('   Waiting for state to settle...');
    await sleep(3000);

    // The replacement lock amount must not exceed current balance.
    // Note: after the initial 5000 DAG lock, the available balance is (original - 5000),
    // so when amount=max the replacement lock will be smaller than the original balance.
    // Floor the amount to avoid precision issues with fractional DAG.
    const currentBalance = await dag4.network.getAddressBalance(address);
    const currentBalanceDag = currentBalance.balance / 100000000;
    const replacementAmount = Math.floor(Math.min(amount, currentBalanceDag));
    console.log(`   Current balance: ${currentBalanceDag} DAG`);
    console.log(`   Replacing with amount: ${replacementAmount} DAG`);

    const replacedLockResult = await createTokenLockForAmount(account, urls, replacementAmount, {
        replaceTokenLockRef: initialTokenLockHash
    });
    const replacedTokenLockHash = replacedLockResult.transactionHash;
    console.log(`   New token lock hash: ${replacedTokenLockHash}`);

    // Verify new token lock is created
    console.log('\n📋 STEP 4b: Verify new token lock is created');
    console.log('-'.repeat(50));

    const verifiedReplacedLock = await pollForTokenLock(account, urls, replacedTokenLockHash);
    console.log(`   Verified new amount: ${verifiedReplacedLock.amount / 100000000} DAG`);
    if (verifiedReplacedLock.amount !== replacementAmount * 100000000) {
        throw new Error(`Replaced token lock amount mismatch: expected ${replacementAmount * 100000000}, got ${verifiedReplacedLock.amount}`);
    }
    console.log(`✅ Replaced token lock verified successfully`);

    // Verify delegated stake is updated
    console.log('\n📋 STEP 4c: Verify delegated stake amount and token lock ref are updated');
    console.log('-'.repeat(50));

    const updatedStake = await pollForDelegatedStake(urls, address, {
        tokenLockRef: replacedTokenLockHash,
        nodeId: nodeId,
        amount: replacementAmount * 100000000
    });
    console.log(`   Original stake hash: ${verifiedStake.hash}`);
    console.log(`   Updated stake hash: ${updatedStake.hash}`);
    console.log(`   Updated amount: ${updatedStake.amount / 100000000} DAG`);
    console.log(`   Updated token lock ref: ${updatedStake.tokenLockRef}`);

    // Verify the stake hash is the same (existing stake was updated, not a new one created)
    if (updatedStake.hash !== verifiedStake.hash) {
        throw new Error(`Delegated stake hash changed! Expected existing stake ${verifiedStake.hash} to be updated, but got new stake ${updatedStake.hash}`);
    }
    console.log(`✅ Confirmed: existing stake was updated (not a new stake)`);

    if (updatedStake.tokenLockRef !== replacedTokenLockHash) {
        throw new Error(`Delegated stake token lock ref not updated: expected ${replacedTokenLockHash}, got ${updatedStake.tokenLockRef}`);
    }
    if (updatedStake.amount !== replacementAmount * 100000000) {
        throw new Error(`Delegated stake amount not updated: expected ${replacementAmount * 100000000}, got ${updatedStake.amount}`);
    }
    console.log(`✅ Delegated stake update verified successfully`);

    // Step 5: Withdraw delegated stake
    console.log('\n📋 STEP 5: Withdraw delegated stake');
    console.log('-'.repeat(50));

    const withdrawResult = await withdrawDelegatedStake(account, updatedStake.hash);
    console.log(`   Withdrawal transaction hash: ${withdrawResult.transactionHash}`);

    // Verify withdrawal is created
    console.log('\n📋 STEP 5b: Verify pending withdrawal is created');
    console.log('-'.repeat(50));

    const verifiedWithdrawal = await pollForPendingWithdrawal(urls, address, updatedStake.hash);
    console.log(`   Verified withdrawal hash: ${verifiedWithdrawal.hash}`);
    console.log(`   Verified amount: ${verifiedWithdrawal.amount / 100000000} DAG`);
    console.log(`✅ Pending withdrawal verified successfully`);

    // Summary
    console.log('\n' + '='.repeat(50));
    console.log('🎉 SMOKE TEST COMPLETED SUCCESSFULLY');
    console.log('='.repeat(50));
    console.log(`\nSummary:`);
    console.log(`  - Node ID: ${nodeId}`);
    console.log(`  - Initial token lock (5000 DAG): ${initialTokenLockHash}`);
    console.log(`  - Delegated stake: ${delegatedStakeHash}`);
    console.log(`  - Replaced token lock (${replacementAmount} DAG): ${replacedTokenLockHash}`);
    console.log(`  - Withdrawal initiated for stake: ${updatedStake.hash}`);
    console.log('\n✅ All verification steps passed!');
}

export { transferDag, extractKeysAndAccount, createTokenLockForAmount, createDelegatedStake, withdrawDelegatedStake, connectAndLogin, getActiveTokenLocksTransactions, computeHash, downloadCombinedSnapshot, createNodeParams, smokeTest };
