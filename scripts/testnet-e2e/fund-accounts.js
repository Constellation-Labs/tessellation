// Funds the four e2e test accounts (TEST_PRIVATE_KEY_1..4) from a funder wallet
// on a live network, then waits for finalization. Fee >= 200000 datum bypasses
// the dag-l1 rate limiter (min-fee-without-limit).
//
// Env: FUNDER_PRIVATE_KEY (required), GL0_URL, GL1_URL,
//      FUND_DAG (per account, default 50), TEST_PRIVATE_KEY_1..4 (required)
const { dag4 } = require('@stardust-collective/dag4');

const GL0 = process.env.GL0_URL;
const GL1 = process.env.GL1_URL;
const FUND_DAG = parseFloat(process.env.FUND_DAG || '50');
const FEE_DAG = 0.002; // 200000 datum = rate-limit bypass
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function balanceOf(address) {
  const res = await fetch(`${GL0}/dag/${address}/balance`);
  if (!res.ok) return 0;
  const body = await res.json();
  return (body.balance || 0) / 1e8;
}

async function main() {
  if (!process.env.FUNDER_PRIVATE_KEY || !GL0 || !GL1) {
    throw new Error('FUNDER_PRIVATE_KEY, GL0_URL and GL1_URL are required');
  }
  const keys = [1, 2, 3, 4].map((i) => {
    const k = process.env[`TEST_PRIVATE_KEY_${i}`];
    if (!k) throw new Error(`TEST_PRIVATE_KEY_${i} missing`);
    return k;
  });

  const funder = dag4.createAccount();
  funder.loginPrivateKey(process.env.FUNDER_PRIVATE_KEY);
  funder.connect({ networkVersion: '2.0', l0Url: GL0, l1Url: GL1 }, false);

  const targets = keys.map((k) => {
    const a = dag4.createAccount();
    a.loginPrivateKey(k);
    return a.address;
  });

  const funderBalance = await balanceOf(funder.address);
  console.log(`funder ${funder.address} balance=${funderBalance} DAG; funding ${targets.length} x ${FUND_DAG} DAG`);
  if (funderBalance < targets.length * (FUND_DAG + FEE_DAG)) {
    throw new Error('funder balance too low');
  }

  // Sequential sends: a single source address is one in-order tx chain.
  // transferDag re-reads the node's last-reference each call, which only advances
  // once the prior tx is accepted -- so wait for the ref to move between sends.
  const lastRefOf = async () => {
    const r = await fetch(`${GL1}/transactions/last-reference/${funder.address}`);
    return (await r.json()).ordinal;
  };
  // Per-account override: FUND_DAG_1..4 (e.g. token-lock tests need a 6000 DAG
  // lock on account 1); falls back to FUND_DAG.
  const fundFor = (i) => parseFloat(process.env[`FUND_DAG_${i + 1}`] || `${FUND_DAG}`);
  for (const [i, addr] of targets.entries()) {
    const FUND_DAG = fundFor(i);
    if ((await balanceOf(addr)) >= FUND_DAG * 0.9) {
      console.log(`already funded: ${addr}`);
      continue;
    }
    const before = await lastRefOf();
    // A Conflict means an earlier tx from the funder (e.g. from a previous run
    // or sweep) still occupies the next ordinal unfinalized; wait a round and
    // retry until the chain frees up.
    let tx;
    for (let attempt = 1; ; attempt++) {
      try {
        tx = await funder.transferDag(addr, FUND_DAG, FEE_DAG);
        break;
      } catch (e) {
        const msg = (e.message || String(e));
        if (!msg.includes('Conflict') || attempt >= 20) throw e;
        console.log(`funder chain busy (pending tx ahead), waiting... [${attempt}]`);
        await sleep(20000);
      }
    }
    console.log(`sent ${FUND_DAG} DAG -> ${addr} hash=${tx.hash || JSON.stringify(tx).slice(0, 64)}`);
    for (let i = 0; i < 30 && (await lastRefOf()) <= before; i++) await sleep(2000);
  }

  // Wait for finalization: every target visible at >= 90% of the funded amount.
  const deadline = Date.now() + 30 * 60 * 1000;
  for (;;) {
    const balances = await Promise.all(targets.map(balanceOf));
    const funded = balances.filter((b, i) => b >= fundFor(i) * 0.9).length;
    console.log(`funded ${funded}/${targets.length} (${balances.map((b) => b.toFixed(1)).join(',')})`);
    if (funded === targets.length) break;
    if (Date.now() > deadline) throw new Error('funding did not finalize within 30m');
    await sleep(20000);
  }
  console.log('all test accounts funded');
}

main().catch((e) => { console.error(e.message || e); process.exit(1); });
