#!/usr/bin/env node
/**
 * Background transaction sender for E2E tests.
 * Sends periodic DAG transfers to GL1 to trigger EventTrigger consensus,
 * ensuring ordinals advance between TimeTrigger cycles.
 *
 * Usage: node send-background-txns.js [gl1_url] [interval_ms]
 *   gl1_url      - GL1 node URL (default: http://localhost:9100)
 *   interval_ms  - Send interval in ms (default: 5000)
 *
 * Requires: @stardust-collective/dag4 (installed in CI)
 *
 * Stops gracefully on SIGTERM/SIGINT.
 */

const dag4 = require('@stardust-collective/dag4').dag4

const GL1_URL = process.argv[2] || 'http://localhost:9100'
const INTERVAL_MS = parseInt(process.argv[3] || '5000', 10)

// Genesis wallet seed phrases (test wallets, duplicated from shared.js for independence)
const SEED1 = 'drift doll absurd cost upon magic plate often actor decade obscure smooth'
const SEED2 = 'enroll galaxy category door able ostrich congress engine marriage galaxy drastic planet'

let running = true
let txCount = 0

process.on('SIGTERM', () => { running = false })
process.on('SIGINT', () => { running = false })

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms))

async function main() {
  console.log(`[bg-txns] Starting: GL1=${GL1_URL} interval=${INTERVAL_MS}ms`)

  const config = {
    networkVersion: '2.0',
    l1Url: GL1_URL,
    testnet: true,
  }

  const account1 = dag4.createAccount()
  account1.loginSeedPhrase(SEED1)
  account1.connect(config)

  const account2 = dag4.createAccount()
  account2.loginSeedPhrase(SEED2)
  account2.connect(config)

  console.log(`[bg-txns] Sender: ${account1.address}`)
  console.log(`[bg-txns] Receiver: ${account2.address}`)

  // Alternate sending direction each time
  let forward = true

  while (running) {
    try {
      const from = forward ? account1 : account2
      const to = forward ? account2 : account1
      await from.transferDag(to.address, 1, 0)
      txCount++
      if (txCount % 10 === 0) {
        console.log(`[bg-txns] Sent ${txCount} transactions`)
      }
    } catch (err) {
      // Transient errors are expected (node not ready, network partition, etc.)
      if (txCount === 0) {
        console.log(`[bg-txns] First tx failed (node may not be ready): ${err.message || err}`)
      }
    }
    forward = !forward
    await sleep(INTERVAL_MS)
  }

  console.log(`[bg-txns] Stopped after ${txCount} transactions`)
}

main().catch(err => {
  console.error(`[bg-txns] Fatal: ${err.message || err}`)
  process.exit(1)
})
