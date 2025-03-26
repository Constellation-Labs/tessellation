const axios = require("axios")
const { dag4 } = require('@stardust-collective/dag4');
const jsSha256 = require('js-sha256');
const { compress } = require("brotli");

const sortAndRemoveNulls = (obj) => {
  const processValue = (value) => {
    if (value === null) return undefined;
    if (Array.isArray(value)) {
      return value.map(v => processValue(v)).filter(v => v !== undefined);
    }
    if (typeof value === 'object') {
      return sortAndRemoveNulls(value);
    }
    return value;
  };

  const cleanObj = Object.entries(obj)
    .reduce((acc, [key, value]) => {
      const processed = processValue(value);
      if (processed !== undefined) {
        acc[key] = processed;
      }
      return acc;
    }, {});

  return Object.keys(cleanObj)
    .sort()
    .reduce((acc, key) => {
      acc[key] = cleanObj[key];
      return acc;
    }, {});
};

const sortedJsonStringify = (obj) => JSON.stringify(sortAndRemoveNulls(obj));

const brotliSerialize = async (content, compressionLevel = 2) => {
  const jsonString = sortedJsonStringify(content);
  const encoder = new TextEncoder();
  const utf8Bytes = encoder.encode(jsonString);
  return compress(utf8Bytes, { quality: compressionLevel });
};

// const brotliSerialize = async (content, compressionLevel = 9) => {
//   try {
//     const jsonString = sortedJsonStringify(content);
//     console.log('JSON String:', jsonString);

//     const encoder = new TextEncoder();
//     const utf8Bytes = encoder.encode(jsonString);
    
//     console.log('UTF-8 Bytes length:', utf8Bytes.length);
    
//     const compressed = compress(utf8Bytes, { quality: compressionLevel });
    
//     console.log('Compressed length:', compressed ? compressed.length : 'null');
//     return compressed;
//   } catch (error) {
//     console.error('Brotli serialization error:', error);
//     throw error;
//   }
// };

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

const generateProofWithSerializerBase64 = async (message, walletPrivateKey, account) => {
  const encodedMessage = Buffer.from(JSON.stringify(message)).toString('base64')
  const signature = await dag4.keyStore.dataSign(walletPrivateKey, encodedMessage);

  const publicKey = account.publicKey;
  const cleanPublicKey = publicKey.length === 130 ? publicKey.substring(2) : publicKey;

  return { id: cleanPublicKey, signature };
};

const getCurrentSyncEpochProgress = async (l0Url) => {
  const response = await axios.get(`${l0Url}/snapshots/latest`)
  return response.data.value.globalSyncView.epochProgress
}


const createNodeParams = async (account) => {
  try {
    return {
      "source": account.address,
      "delegatedStakeRewardParameters": {
        "rewardFraction": 10000000,

      },
      "nodeMetadataParameters": {
        "name": "TestnetSourceNode3",
        "description": "TestnetSourceNode3"
      },
      "parent": {
        "ordinal": 0,
        "hash": "0000000000000000000000000000000000000000000000000000000000000000"
      }
    };
  } catch (e) {
    throw e
  }
};

const createDelegatedStake = async (account) => {
  try {
    return {
      "source": account.address,
      "nodeId": "bc43050d3a02a7909cfe254be069d62c913a20addc1daafc683a95c805d47f158b76149abb81bf1c39f614219c528325bbfbd20b8095d476e8589eca199d2180",
      "amount": 1,
      "fee": 0,
      "tokenLockRef": "d2ae911acf62c2242053deaf1374a99045fcda5f6c5159bca90c5e35eb1b3426",
      "parent": {
        "ordinal": 1,
        "hash": "443e446071ad037d3e75c66b965d17755c831c806f20d8a14b28d7face51c1ef"
      }
    };
  } catch (e) {
    throw e
  }
};
const withdrawalDelegatedStaking = async (account) => {
  try {
    return {
      "source": account.address,
      "stakeRef": "4f5b99e4e77a79e6e491e856684cf396f66ce39a2dc0b1ff08a38b8cdbb50ee9"
    };
  } catch (e) {
    throw e
  }
};

const createTokenLockTransaction = async (sourceAccount, l1Url) => {
  try {
    console.log(`TEST: ${`${l1Url}/token-locks/last-reference/${sourceAccount.address}`}`)
    const [{ data: lastRef }] = await Promise.all([
      axios.get(`${l1Url}/token-locks/last-reference/${sourceAccount.address}`)
    ]);


    return {
      amount: 1,
      currencyId: null,
      fee: 0,
      parent: lastRef,
      source: sourceAccount.address,
      unlockEpoch:null,
    }
  } catch (e) {
    throw e
  }
};

const main = async () => {
  const dagL0Url1 = "http://52.8.132.193:9000"
  const dagL0Url2 = "http://13.57.110.45:9000"
  const dagL0Url3 = "http://54.153.23.79:9000"
  
  const dagL1Url1 = "http://localhost:9100"

  const node1PrivateKey = "defe9f15015a313e01cd84543cf31a9bb6f54980e24d21282911e044b3ad6b94"
  const node2PrivateKey = "6466e26f4e0976aa63d00e28b8cc81318071e7038af2174852767cdda2932dda"
  const node3PrivateKey = "afb220e01343305f4c8749de5b2af0f93ff900b174e876b96f412972758bf85c"
  const delegatedPrivateKey = "74ac1a29d23d980b6cd5e547250d5e4215c8807e8d1092d049f0a9f9bb818962"

  const account = dag4.createAccount(delegatedPrivateKey)

  const nodeParams = await createDelegatedStake(account,dagL1Url1)
console.log(JSON.stringify(nodeParams))
  const proof1 = await generateProofWithSerializer(nodeParams, delegatedPrivateKey, account)
  const content1 = {
    value: nodeParams,
    proofs: [{ ...proof1 }]
  }

  // const allowSpend2 = await createAllowSpendTransaction(account, cl2MetagraphId, c2l1Url, epochProgressCl2)
  // const proof2 = await generateProofWithSerializer(allowSpend2, privateKey, account)
  // const content2 = {
  //   value: allowSpend2,
  //   proofs: [{ ...proof2 }]
  // }

  console.log(JSON.stringify(content1))
  // console.log(JSON.stringify(content2))
  // const response1 = await axios.post(`${c1l1Url}/allow-spends`, content1)
  // const response2 = await axios.post(`${c2l1Url}/allow-spends`, content2)

  // console.log(JSON.stringify(response1.data))
  // console.log(JSON.stringify(response2.data))
  return
}

main()