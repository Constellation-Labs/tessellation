const { dag4 } = require('@stardust-collective/dag4');
const axios = require('axios');
const jsSha256 = require('js-sha256');
const fs = require("fs");
const forge = require("node-forge");
const elliptic = require('elliptic');
const { compress } = require("brotli");

const {
    parseSharedArgs,
    CONSTANTS: sharedConstants,
    PRIVATE_KEYS,
    sleep,
    withRetry,
    generateProof,
    SerializerType,
    createAndConnectAccount,
    createNetworkConfig,
    getEpochProgress,
    createSerializer,
    sortedJsonStringify,
    logWorkflow
} = require('../shared');

const createConfig = () => {
    const args = process.argv.slice(2);

    if (args.length < 5) {
        throw new Error(
            "Usage: node script.js <dagl0-port-prefix> <dagl1-port-prefix> <ml0-port-prefix> <cl1-port-prefix> <datal1-port-prefix>"
        );
    }

    const sharedArgs = parseSharedArgs(args.slice(0, 5));
    return { ...sharedArgs };
};

const logMessage = ( message ) => {
    const formattedMessage = {
        message
    };
    console.log( formattedMessage );
};

// Function to read the private key from the file and extract the public key
function extractPublicKeyFromFile(filePath) {
    // Read the private key from the file in hex format
    const privateKeyHex = fs.readFileSync(filePath, 'utf8').trim(); // Read and trim the hex string

    // Convert the hex string to raw bytes
    const privateKeyBuffer = Buffer.from(privateKeyHex, 'hex');

    try {
        const ec = new elliptic.ec('secp256k1'); 

        const privateKeyString = privateKeyBuffer.toString('hex')
        // Create the EC keypair from the private key bytes
        const keyPair = ec.keyFromPrivate(privateKeyBuffer);

        // Extract the public key in compressed hex format
        const uncompressedPublicKey = keyPair.getPublic(false, 'hex'); // Uncompressed format
        const nodeId = uncompressedPublicKey.slice(2); // Remove the '0x04' prefix

        // Output the public key in hex
        return { privateKeyString, nodeId }
    } catch (error) {
        console.error('Error processing the private key:', error);
    }
}


const createNodeParams = async (account, parametersName, rewardFraction, parent) => {
    try {
      return {
        "source": account.address,
        "delegatedStakeRewardParameters": {
          "rewardFraction": rewardFraction,
  
        },
        "nodeMetadataParameters": {
          "name": parametersName,
          "description": parametersName
        },
        "parent": parent
      };
    } catch (e) {
      throw e
    }
  };

  const brotliSerialize = async (content, compressionLevel = 2) => {
    const jsonString = sortedJsonStringify(content);
    const encoder = new TextEncoder();
    const utf8Bytes = encoder.encode(jsonString);
    return compress(utf8Bytes, { quality: compressionLevel });
  };

  const generateProofWithSerializer = async (message, walletPrivateKey, account) => {
    try {
      const serializedTx = await brotliSerialize(message);
      
      if (!serializedTx) {
        throw new Error('Serialization returned null or undefined');
      }
  
      // Convert compressed buffer to hex string for hashing
      const messageHash = jsSha256.sha256(serializedTx);
      const signature = await dag4.keyStore.sign(walletPrivateKey, messageHash);
  
      const publicKey = account.publicKey;
      const cleanPublicKey = publicKey.length === 130 ? publicKey.substring(2) : publicKey;
  
      return { id: cleanPublicKey, signature };
    } catch (error) {
      console.error('Proof generation error:', error);
      throw error;
    }
  };


const checkOk = async (response) => {
    if (response.status !== 200) throw new Error(`Node returned ${response.status} instead of 200`);
}

const checkbadRequest = async (response) => {
    if (response.status !== 400) throw new Error(`Node returned ${response.status} instead of 400`);
}

const getNodeParams = async (urls) => {
    logWorkflow.info(`Request to: ${urls.globalL0Url}/node-params`);
    const response = await axios.get(`${urls.globalL0Url}/node-params?t=${Date.now()}`, {
        headers: {
            'Cache-Control': 'no-cache, no-store, must-revalidate',
            'Pragma': 'no-cache',
            'Expires': '0'
        }
    });
    checkOk(response);
    return response.data;
};

const verifyInitialNodeParams = async (response) => {
    if (response.length !== 0) throw new Error(`Initial node parameters shall be empty but received ${initialNodeParams}`);

}

const extractKeysAndAccount = (filePath) => {
    const { privateKeyString, nodeId } = extractPublicKeyFromFile(filePath);
    logWorkflow.info(`Extracted node id: ${nodeId}`);
    logWorkflow.info(`Extracted private key: ${privateKeyString}`);
    const account = dag4.createAccount(privateKeyString);
    logWorkflow.info(`Extracted address: ${account.address}`);
    return { privateKeyString, nodeId, account };
};

const postNodeParamsNodeId = async (urls, nodeId, account, privateKeyString, parameterName, rewardFaction) => {
    let parent = { ordinal: 0, hash: "0000000000000000000000000000000000000000000000000000000000000000" };

    try {
        const response = await axios.get(`${urls.globalL0Url}/node-params/${nodeId}?t=${Date.now()}`, {
            headers: {
                'Cache-Control': 'no-cache, no-store, must-revalidate',
                'Pragma': 'no-cache',
                'Expires': '0'
            }
        });
        if (response.status === 200 && response.data) {
            parent = response.data.lastRef;
        }
    } catch (error) {
        console.info(`Failed to fetch parent reference for node ${nodeId}, using default parent`);
    }
        
    const unsignedNodeParams = await createNodeParams(account, parameterName, rewardFaction, parent);
    const proof1 = await generateProofWithSerializer(unsignedNodeParams, privateKeyString, account);
    const content = { value: unsignedNodeParams, proofs: [{ ...proof1 }] };

    try {
        const updateResponse = await axios.post(`${urls.globalL0Url}/node-params`, content);
        await sleep(2000)
        return updateResponse;
    } catch (error) {
        if (axios.isAxiosError(error)) {
            return error.response; 
        } else {
            throw error;
        }
    }
};


const checkInitialNodeParamsNode = async (urls, nodeId) => {
    try {
        const response = await axios.get(`${urls.globalL0Url}/node-params/${nodeId}?t=${Date.now()}`, {
            headers: {
                'Cache-Control': 'no-cache, no-store, must-revalidate',
                'Pragma': 'no-cache',
                'Expires': '0'
            }
        });
        throw new Error(`Initial ${urls.globalL0Url}/node-params/${nodeId} shal not be defined`)
    } catch (error) {

    }
};

const verifyNodeParamsResponse = (nodeParams, nodeId, expectedName, expectedRewardFraction) => {
    const data = nodeParams.find(item => item.node.id === nodeId);
    if (!data)
        throw new Error(`Node id is not correct`);
    if (data.nodeMetadataParameters.name !== expectedName)
        throw new Error(`Node parameters name expected ${expectedName} but received ${data.nodeMetadataParameters.name}`);
    if (data.delegatedStakeRewardParameters.rewardFraction !== expectedRewardFraction)
        throw new Error(`Node parameters rewardFraction expected ${expectedRewardFraction} but received ${data.delegatedStakeRewardParameters.rewardFraction}`);
};


const getNodeParamsNodeIdVerify = async (urls, nodeId, expectedName, expectedRewardFraction, expectedOrdinal) => {
    const response = await axios.get(`${urls.globalL0Url}/node-params/${nodeId}?t=${Date.now()}`, {
        headers: {
            'Cache-Control': 'no-cache, no-store, must-revalidate',
            'Pragma': 'no-cache',
            'Expires': '0'
        }
    });
    if (response.status !== 200) throw new Error(`NodeParamsNode returned ${response.status} instead of 200`);
    const receivedRewardFraction = response.data.latest.value.delegatedStakeRewardParameters.rewardFraction
    if (receivedRewardFraction !== expectedRewardFraction)
        throw new Error(`Node parameters node rewardFraction expected ${expectedRewardFraction} but received ${receivedRewardFraction}`);
    
    const receivedName = response.data.latest.value.nodeMetadataParameters.name
    if (receivedName !== expectedName)
        throw new Error(`Node parameters node name expected ${expectedName} but received ${receivedName}`);

    const receivedOrdinal = response.data.latest.value.parent.ordinal
    if (receivedOrdinal !== expectedOrdinal)
        throw new Error(`Node parameters node name expected expected 0 ordinal but received ${receivedOrdinal}`);

}

const firstNodeParameterName1 = "FirstNode1";
const firstNodeFraction1 = 10000000;

const firstNodeParameterName2 = "FirstNode2";
const firstNodeFraction2 = 5000000;

const secondNodeParameterName1 = "SecondNode1";
const secondNodeFraction1 = 6000000;

const executeWorkflow2 = async (urls) => {
    logWorkflow.info('Start');
    const initialNodeParams = await getNodeParams(urls);
    await verifyInitialNodeParams(initialNodeParams)
    logWorkflow.info('Initial node params is OK');

    const { privateKeyString: privateKeyString1, nodeId: nodeId1, account: account1 } = extractKeysAndAccount('../../code/hypergraph/dag-l0/genesis-node/id_ecdsa.hex');

    checkInitialNodeParamsNode(urls, nodeId1);
    logWorkflow.info('Check initaial node params is OK');

    const ur1 = await postNodeParamsNodeId(urls, nodeId1, account1, privateKeyString1, firstNodeParameterName1, firstNodeFraction1);
    checkOk(ur1)
    logWorkflow.info('Update node params is OK');

    const nodeParamsAfterUpdate = await getNodeParams(urls);
    verifyNodeParamsResponse(nodeParamsAfterUpdate, nodeId1, firstNodeParameterName1, firstNodeFraction1);
    logWorkflow.info('Check updates node params is OK');

    getNodeParamsNodeIdVerify(urls, nodeId1, firstNodeParameterName1, firstNodeFraction1, 0)
    logWorkflow.info('Check updates node params node is OK');

    const ur2 = await postNodeParamsNodeId(urls, nodeId1, account1, privateKeyString1, firstNodeParameterName2, firstNodeFraction2);
    checkOk(ur2)
    logWorkflow.info('Update node params second time is OK');

    const nodeParamsAfterSecondUpdate = await getNodeParams(urls);
    verifyNodeParamsResponse(nodeParamsAfterSecondUpdate, nodeId1, firstNodeParameterName2, firstNodeFraction2);
    logWorkflow.info('Check second updates node params is OK');

    getNodeParamsNodeIdVerify(urls, nodeId1, firstNodeParameterName2, firstNodeFraction2, 1);
    logWorkflow.info('Check second updates node params node is OK');

    //Send incorrect amount
    const ur3 = await postNodeParamsNodeId(urls, nodeId1, account1, privateKeyString1, firstNodeParameterName2, 10000001);
    checkbadRequest(ur3)

    getNodeParamsNodeIdVerify(urls, nodeId1, firstNodeParameterName2, firstNodeFraction2, 1);
    logWorkflow.info('Check updating node with incorrect params is OK');

    const { privateKeyString: privateKeyString2, nodeId: nodeId2, account: account2 } = extractKeysAndAccount('../../code/hypergraph/dag-l0/validator-1/id_ecdsa.hex');

    const ur4 = await postNodeParamsNodeId(urls, nodeId2, account2, privateKeyString2, secondNodeParameterName1, secondNodeFraction1);
    checkOk(ur4)
    getNodeParamsNodeIdVerify(urls, nodeId2, secondNodeParameterName1, secondNodeFraction1, 0)
    logWorkflow.info('Update second node params is OK');

    const bothNodesParams = await getNodeParams(urls);
    verifyNodeParamsResponse(bothNodesParams, nodeId1, firstNodeParameterName2, firstNodeFraction2);
    verifyNodeParamsResponse(bothNodesParams, nodeId2, secondNodeParameterName1, secondNodeFraction1);
    logWorkflow.info('Both nodes check is OK');
};

const executeWorkflowByType = async (workflowType) => {
    const config = createConfig();
    const urls = createNetworkConfig(config);

    switch (workflowType) {
        case 'check':
            await executeWorkflow2(urls);
            break;
        default:
            throw new Error(`Unknown workflow type: ${workflowType}`);
    }
}

const workflowType = process.argv[7];
if (!workflowType) {
    throw new Error('Workflow type must be specified as the 6th argument');
}

executeWorkflowByType(workflowType);
