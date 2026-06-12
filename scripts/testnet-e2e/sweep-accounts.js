// Sweeps remaining balances from recorded ephemeral test keys back to the
// funder. Reads every key ever recorded in KEYS_FILE (jsonl, one key per line),
// so orphans from aborted runs are recovered too.
// Env: FUNDER_PRIVATE_KEY (destination derived from it), GL0_URL, GL1_URL,
//      KEYS_FILE (default ~/.tessellation-testnet-e2e/keys.jsonl)
const { dag4 } = require('@stardust-collective/dag4');
const fs = require('fs');
const os = require('os');
const path = require('path');

const GL0 = process.env.GL0_URL;
const GL1 = process.env.GL1_URL;
const FEE_DAG = 0.002;
const KEYS_FILE = process.env.KEYS_FILE || path.join(os.homedir(), '.tessellation-testnet-e2e', 'keys.jsonl');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function balanceOf(address) {
  const res = await fetch(`${GL0}/dag/${address}/balance`);
  if (!res.ok) return 0;
  return ((await res.json()).balance || 0) / 1e8;
}

async function main() {
  const funder = dag4.createAccount();
  funder.loginPrivateKey(process.env.FUNDER_PRIVATE_KEY);
  if (!fs.existsSync(KEYS_FILE)) { console.log(`no keys file at ${KEYS_FILE}, nothing to sweep`); return; }
  const keys = [...new Set(fs.readFileSync(KEYS_FILE, 'utf8').split('\n').filter(Boolean))];
  console.log(`sweeping ${keys.length} recorded keys -> ${funder.address}`);

  let swept = 0;
  for (const k of keys) {
    const a = dag4.createAccount();
    a.loginPrivateKey(k);
    const bal = await balanceOf(a.address);
    if (bal <= FEE_DAG * 2) continue; // dust / already swept
    a.connect({ networkVersion: '2.0', l0Url: GL0, l1Url: GL1 }, false);
    const amount = Math.floor((bal - FEE_DAG) * 1e8) / 1e8;
    try {
      await a.transferDag(funder.address, amount, FEE_DAG);
      console.log(`swept ${amount} DAG from ${a.address}`);
      swept++;
    } catch (e) {
      console.log(`sweep failed for ${a.address}: ${(e.message || e).toString().slice(0, 120)}`);
    }
    await sleep(500); // independent chains; light pacing only
  }
  console.log(`sweep submitted for ${swept} addresses (finalization takes ~1-2 rounds)`);
}

main().catch((e) => { console.error(e.message || e); process.exit(1); });
