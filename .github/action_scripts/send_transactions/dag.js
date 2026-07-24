const { dag4 } = require('@stardust-collective/dag4');
const axios = require('axios');

// --- Config ---------------------------------------------------------------
// Nightly global cluster (n0). Override with GL0_URL / GL1_URL if needed.
const GL0_URL = process.env.GL0_URL || 'http://89.167.66.37:9000';
const GL1_URL = process.env.GL1_URL || 'http://89.167.66.37:9010';

// One of the nightly genesis-funded private keys (key1 from
// shared/constants.js -> DAG5sz69nNwGF8ypn1yukFpg2pVJpdx5mnf1PJVc, 100k DAG).
const SENDER_PRIVATE_KEY =
  process.env.SENDER_PRIVATE_KEY ||
  '595a30ab6c62ae48a23414951e2703f49f8c0040b9801738ad3550475389d811';

// The receiver was supplied as a 64-char hex private key; derive its DAG
// address (a DAG transaction destination must be a DAG-prefixed address).
const RECEIVER_PRIVATE_KEY =
  process.env.RECEIVER_PRIVATE_KEY ||
  '1f25d34e562b8989d3b36b52ae4c4217fbe5f0b5544cf7a8e948980d5829a76a';

const AMOUNT_DAG = Number(process.env.AMOUNT_DAG || 10000); // whole DAG units
const FEE_DAG = Number(process.env.FEE_DAG || 0);

const MAX_VERIFY_ATTEMPTS = 120;
const VERIFY_INTERVAL_MS = 2000;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (m) => console.log(`[${new Date().toISOString()}] ${m}`);

// Authoritative balance read straight from global-L0 (datum -> whole DAG).
const getBalanceDag = async (address) => {
  const { data } = await axios.get(`${GL0_URL}/dag/${address}/balance`, {
    timeout: 8000,
  });
  return Number(data.balance) / 1e8;
};

async function main() {
  // Resolve addresses.
  const sender = dag4.createAccount();
  sender.loginPrivateKey(SENDER_PRIVATE_KEY);

  const receiver = dag4.createAccount();
  receiver.loginPrivateKey(RECEIVER_PRIVATE_KEY);
  const receiverAddress = receiver.address;

  log(`GL0:      ${GL0_URL}`);
  log(`GL1:      ${GL1_URL}`);
  log(`Sender:   ${sender.address}`);
  log(`Receiver: ${receiverAddress}`);
  log(`Amount:   ${AMOUNT_DAG} DAG (fee ${FEE_DAG})`);

  await sender.connect({
    networkVersion: '2.0',
    l0Url: GL0_URL,
    l1Url: GL1_URL,
    testnet: true,
  });

  const senderBefore = await getBalanceDag(sender.address);
  const receiverBefore = await getBalanceDag(receiverAddress);
  log(`Before -> sender: ${senderBefore} DAG, receiver: ${receiverBefore} DAG`);

  if (senderBefore < AMOUNT_DAG + FEE_DAG) {
    throw new Error(
      `Sender has insufficient balance: ${senderBefore} < ${AMOUNT_DAG + FEE_DAG}`
    );
  }

  // Submit the transfer (dag4 takes whole-DAG amounts and converts internally).
  // transferDag returns the posted transaction object: { hash, ... }.
  const tx = await sender.transferDag(receiverAddress, AMOUNT_DAG, FEE_DAG);
  const hash = typeof tx === 'string' ? tx : tx && tx.hash;
  if (!hash) throw new Error('transferDag returned no hash');
  log(`Submitted transaction. Hash: ${hash}`);

  // Verify: poll global-L0 until the receiver's balance reflects the transfer.
  const expected = receiverBefore + AMOUNT_DAG;
  log(`Waiting for receiver balance to reach ${expected} DAG...`);

  for (let attempt = 1; attempt <= MAX_VERIFY_ATTEMPTS; attempt++) {
    const receiverNow = await getBalanceDag(receiverAddress);
    if (receiverNow >= expected) {
      const senderNow = await getBalanceDag(sender.address);
      log(`After  -> sender: ${senderNow} DAG, receiver: ${receiverNow} DAG`);
      log(
        `SUCCESS: receiver ${receiverAddress} received ${receiverNow - receiverBefore} DAG.`
      );
      return;
    }
    if (attempt % 5 === 0) {
      log(`  ...attempt ${attempt}/${MAX_VERIFY_ATTEMPTS}: receiver at ${receiverNow} DAG`);
    }
    await sleep(VERIFY_INTERVAL_MS);
  }

  const finalBalance = await getBalanceDag(receiverAddress);
  throw new Error(
    `Verification FAILED: receiver balance ${finalBalance} DAG, expected ${expected} DAG ` +
      `after ${MAX_VERIFY_ATTEMPTS} attempts. Tx hash: ${hash}`
  );
}

main()
  .then(() => process.exit(0))
  .catch((err) => {
    log(`ERROR: ${err.message}`);
    if (err.response && err.response.data) {
      console.error(JSON.stringify(err.response.data, null, 2));
    }
    process.exit(1);
  });
