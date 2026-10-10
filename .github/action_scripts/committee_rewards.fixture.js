#!/usr/bin/env node
/**
 * Offline fixture server for committee_rewards.js. Serves a synthetic global-snapshot window that
 * reproduces the state the test hunts for: 3 genesis peers and one late joiner admitted at ordinal 10,
 * seated from ordinal 11 and not signing until ordinal 19. Each snapshot N+1 embeds the certified
 * outcome of round N (certifiedLineage), whose ProposalValue carries round N's frozen committee.
 *
 * WHY THIS EXISTS
 * `node --check` parses but does not resolve identifiers, and the real test needs a 5-node docker
 * cluster plus several minutes of consensus before it evaluates anything. This server exercises the
 * full scan and assertion path in about a second, offline, and -- with --drop-admitted-reward --
 * proves the assertions actually FAIL against the behavior they are meant to catch. Run it after any
 * change to committee_rewards.js; it is not a substitute for the live run, which additionally
 * validates that admission lands inside the expected window on a real cluster.
 *
 * Usage:
 *   node committee_rewards.fixture.js --write-node-fixtures <dir>   # writes <dir>/nodes/<i>/...
 *   node committee_rewards.fixture.js <port> [--drop-admitted-reward]
 *
 *   --drop-admitted-reward simulates a payout that skips the newly admitted member (seated but
 *   unpaid). The test MUST fail in that mode; if it passes, the assertion has no teeth.
 *
 * Full repro (from a repo checkout):
 *   H=$(mktemp -d)
 *   node .github/action_scripts/committee_rewards.fixture.js --write-node-fixtures "$H"
 *   mkdir -p "$H/.github/action_scripts"
 *   ln -s "$PWD/.github/action_scripts/shared" "$H/.github/action_scripts/shared"
 *   ln -s "$PWD/.github/action_scripts/node_modules" "$H/.github/action_scripts/node_modules"
 *   cp .github/action_scripts/committee_rewards.js "$H/.github/action_scripts/"
 *   node .github/action_scripts/committee_rewards.fixture.js 18080 &
 *   (cd "$H/.github/action_scripts" && GL0_URL=http://localhost:18080 NUM_GL0_NODES=5 \
 *      NUM_GL0_EARLY=3 node committee_rewards.js 90 91)   # expect exit 0
 *   # then repeat with --drop-admitted-reward on a fresh port; expect exit 1
 */

const http = require('http')
const fs = require('fs')
const path = require('path')

const port = parseInt(process.argv[2] || '18080', 10)
const DROP_ADMITTED_REWARD = process.argv.includes('--drop-admitted-reward')

// Peer ids / addresses must match what the test reads from nodes/<i>/{peer_id,address}.
const peers = [0, 1, 2, 3, 4].map((i) => ({
  index: i,
  peerId: String(i).repeat(128).slice(0, 128),
  address: `DAG0FIXTUREADDRESS${String(i).repeat(20)}`.slice(0, 40),
}))
const GENESIS = peers.slice(0, 3)
const CLIMBER = peers[3]
const NEVER_SEATED = peers[4]

const ADMISSION_ORDINAL = 10
// The admitted member is SEATED from ADMISSION_ORDINAL+1 but its signature is missing from the proofs
// for a while, so the sampled rounds also exercise "paid by seat, not by signature".
const CLIMBER_FIRST_SIGNS = 19
const HEAD = 20
const REWARD_AMOUNT = 22161532110000

const committeeAt = (ordinal) =>
  ordinal > ADMISSION_ORDINAL
    ? [...GENESIS.map((p) => p.peerId), CLIMBER.peerId]
    : GENESIS.map((p) => p.peerId)

const signersAt = (ordinal) =>
  committeeAt(ordinal).filter((peerId) => peerId !== CLIMBER.peerId || ordinal >= CLIMBER_FIRST_SIGNS)

// TimeTrigger on even ordinals: epochProgress increments there and stays flat on odd ones, giving
// the test both a reward sample and an EventTrigger control.
const isTimeTrigger = (ordinal) => ordinal % 2 === 0
const epochProgressAt = (ordinal) => Math.floor(ordinal / 2)

/** snapshot[N] embeds the certified outcome of round N-1 (absent at the first ordinal). */
const certifiedLineageFor = (ordinal) =>
  ordinal <= 1
    ? undefined
    : {
        parentOutcome: {
          proposalQc: {
            value: {
              key: ordinal - 1,
              roundStartFacilitators: committeeAt(ordinal - 1),
              admittedPeers: ordinal - 1 === ADMISSION_ORDINAL ? [CLIMBER.peerId] : [],
              evictedPeers: [],
            },
            valueHash: 'aa'.repeat(32),
            signatures: [],
          },
          commitQc: { valueHash: 'aa'.repeat(32), signatures: [] },
        },
      }

const rewardsFor = (ordinal) => {
  if (!isTimeTrigger(ordinal)) return []
  const paid = committeeAt(ordinal).filter((peerId) => {
    if (!DROP_ADMITTED_REWARD) return true
    return peerId !== CLIMBER.peerId
  })
  return paid.map((peerId) => ({
    destination: peers.find((p) => p.peerId === peerId).address,
    amount: REWARD_AMOUNT,
  }))
}

const snapshotFor = (ordinal) => ({
  value: {
    ordinal,
    epochProgress: epochProgressAt(ordinal),
    rewards: rewardsFor(ordinal),
    certifiedLineage: certifiedLineageFor(ordinal),
  },
  proofs: signersAt(ordinal).map((id) => ({ id, signature: 'ff'.repeat(32) })),
})

// Bootstrap mode: write the node key fixtures the test reads (nodes/<i>/{peer_id,address}). These
// must match the ids this server serves, or every committee member resolves to an unknown node.
const writeIndex = process.argv.indexOf('--write-node-fixtures')
if (writeIndex !== -1) {
  const root = process.argv[writeIndex + 1]
  if (!root) {
    process.stderr.write('--write-node-fixtures requires a target directory\n')
    process.exit(1)
  }
  for (const peer of peers) {
    const dir = path.join(root, 'nodes', String(peer.index))
    fs.mkdirSync(dir, { recursive: true })
    fs.writeFileSync(path.join(dir, 'peer_id'), peer.peerId)
    fs.writeFileSync(path.join(dir, 'address'), peer.address)
  }
  process.stdout.write(`wrote ${peers.length} node fixtures under ${path.join(root, 'nodes')}\n`)
  process.exit(0)
}

const server = http.createServer((req, res) => {
  const url = req.url.split('?')[0]
  const send = (body) => {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(body))
  }

  if (url === '/global-snapshots/latest') return send(snapshotFor(HEAD))
  if (url === '/cluster/info') {
    return send(peers.map((p) => ({ id: p.peerId, ip: `172.32.0.1${p.index}`, state: 'Ready' })))
  }
  const match = url.match(/^\/global-snapshots\/(\d+)$/)
  if (match) {
    const ordinal = parseInt(match[1], 10)
    if (ordinal < 1 || ordinal > HEAD) {
      res.writeHead(404)
      return res.end('not found')
    }
    return send(snapshotFor(ordinal))
  }
  res.writeHead(404)
  res.end('not found')
})

server.listen(port, () => {
  process.stdout.write(
    `fixture listening on ${port} (climber=${CLIMBER.peerId.slice(0, 4)} admitted at ${ADMISSION_ORDINAL}, ` +
      `never-seated=${NEVER_SEATED.peerId.slice(0, 4)}, dropAdmittedReward=${DROP_ADMITTED_REWARD})\n`,
  )
})
