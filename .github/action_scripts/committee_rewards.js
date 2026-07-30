#!/usr/bin/env node
/**
 * committee_rewards.js -- delegated validator rewards must pay the FULL frozen committee.
 *
 * WHAT THIS PROTECTS (PR #1547, ADR-0028)
 * Delegated rewards follow the frozen round-start signing committee (Core + Tier 1), split
 * equally. The behavior this replaced (`legacyRewardQualifiedFacilitators`) filtered the payout to
 * facilitators whose evidence-derived controller score had already reached the promote threshold,
 * so a peer that was seated and signing but still climbing earned nothing.
 *
 * THE ONE STATE THAT DISCRIMINATES OLD FROM NEW
 * The legacy filter had a fallback: if NO facilitator met the threshold it paid everyone. So a
 * committee where every member is below the threshold (e.g. the first rounds after genesis) is
 * NOT a discriminating sample -- both implementations pay everyone. Nor is a committee where
 * everyone is above it. The regression is visible only on a MIXED committee: at least one member
 * below the promote threshold and at least one at or above it. Under the old code the below-
 * threshold member is dropped from `rewards`; under the current code it is paid the same share as
 * everyone else. This test therefore searches for a mixed committee and fails if it cannot find
 * one, rather than asserting on whatever ordinal happens to be handy.
 *
 * THE RIG (docker/bin/set-env.sh NUM_GL0_EARLY)
 * 5 gl0 nodes: 3 join at genesis, 2 delay their self-join. The genesis peers saturate their score
 * while a late joiner enters at ~0 and climbs (+20 per signed round, promote at 100), which is
 * what manufactures the mixed committee. Requires --num-gl0=5 --num-gl0-early=3.
 *
 * ORACLES (both signed, both consensus-agreed, neither requires tier introspection)
 *   committee(N) = snapshot[N+1].value.peerHistory.controllerEvidence["N"].roundStartFacilitators
 *   scores(N)    = derived from snapshot[N].value.peerHistory.controllerEvidence, which is the
 *                  outcome as of round N's proposal (entries up to N-1) -- the exact window the
 *                  legacy filter read.
 * Per-peer tier is deliberately blanked in the signed artifact, so the Core/Tier-1 union is the
 * right and sufficient oracle: the property under test is that both are paid identically.
 *
 * Static validator rewards mint on TimeTrigger rounds only (EventTrigger passes a zero pool),
 * identified over HTTP by epochProgress[N] > epochProgress[N-1].
 */

const fs = require('fs')
const path = require('path')
const axios = require('axios')

const { parseSharedArgs, createNetworkConfig, withRetryOrdinal, logWorkflow } = require('./shared')

const NO_CACHE_HEADERS = {
  'Cache-Control': 'no-cache, no-store, must-revalidate',
  Pragma: 'no-cache',
  Expires: '0',
}

// Mirrors ControllerEvidenceDerivation (compiled-in constants) and the promote threshold from
// dag-l0.conf. If those move, this test's search stops finding mixed committees and says so.
const SIGN_WEIGHT = 20
const MISS_WEIGHT = 15
const CERT_WEIGHT = 10
const MIN_SCORE = 0
const MAX_SCORE = 150
const PROMOTE_THRESHOLD = parseInt(process.env.CL_ACTIVE_ADMISSION_PROMOTE_THRESHOLD || '100', 10)

const NUM_GL0 = parseInt(process.env.NUM_GL0_NODES || '5', 10)
const NUM_EARLY = parseInt(process.env.NUM_GL0_EARLY || '3', 10)
// Ordinals below the head to search. The mixed window is short (a climber crosses the threshold
// after ~5 signed rounds), so the search must cover enough history to contain it.
const SCAN_DEPTH = parseInt(process.env.COMMITTEE_REWARDS_SCAN_DEPTH || '60', 10)

const fetchJson = async (url) => {
  const response = await axios.get(`${url}${url.includes('?') ? '&' : '?'}t=${Date.now()}`, {
    headers: NO_CACHE_HEADERS,
  })
  if (response.status !== 200) {
    throw new Error(`GET ${url} returned ${response.status}`)
  }
  return response.data
}

/**
 * peer id -> reward address, from the per-node key material the running nodes use
 * (nodes/<i>/{peer_id,address}, synced by docker/bin/node-key-env-setup.sh). Deriving the address
 * in JS instead would mean reimplementing Address.fromBytes (DER prefix -> sha256 -> base58 ->
 * parity), which is exactly the kind of duplicated crypto that drifts from the node.
 * A stale or mis-indexed map cannot pass silently: every committee member must resolve to a known
 * node below, or the test fails.
 */
const loadNodeIdentities = (projectRoot) => {
  const identities = []
  for (let i = 0; i < NUM_GL0; i++) {
    const dir = path.join(projectRoot, 'nodes', String(i))
    const peerIdPath = path.join(dir, 'peer_id')
    const addressPath = path.join(dir, 'address')
    if (!fs.existsSync(peerIdPath) || !fs.existsSync(addressPath)) {
      throw new Error(
        `Missing key material for gl0-${i} at ${dir} (expected peer_id and address). ` +
          `Was the cluster started with --num-gl0=${NUM_GL0}?`,
      )
    }
    identities.push({
      index: i,
      peerId: fs.readFileSync(peerIdPath, 'utf8').trim(),
      address: fs.readFileSync(addressPath, 'utf8').trim(),
      isLateJoiner: i >= NUM_EARLY,
    })
  }
  return identities
}

/** Replicates ControllerEvidenceDerivation.derive's score arithmetic over an evidence window. */
const derivePeerScores = (controllerEvidence) => {
  const entries = Object.values(controllerEvidence || {})
  const scores = new Map()
  const peers = new Set()
  for (const entry of entries) {
    for (const list of [
      entry.roundStartFacilitators,
      entry.completedSigners,
      entry.timeoutVoters,
      entry.admittedPeers,
      entry.evictedPeers,
    ]) {
      for (const peerId of list || []) peers.add(peerId)
    }
  }
  for (const peerId of peers) {
    let completed = 0
    let missed = 0
    let certAppearances = 0
    for (const entry of entries) {
      const signed = (entry.completedSigners || []).includes(peerId)
      const seated = (entry.roundStartFacilitators || []).includes(peerId)
      if (signed) completed++
      if (seated && !signed) missed++
      if ((entry.admittedPeers || []).includes(peerId)) certAppearances++
      if ((entry.timeoutVoters || []).includes(peerId)) certAppearances++
    }
    const raw = completed * SIGN_WEIGHT - missed * MISS_WEIGHT + certAppearances * CERT_WEIGHT
    scores.set(peerId, Math.max(MIN_SCORE, Math.min(MAX_SCORE, raw)))
  }
  return scores
}

const committeeForOrdinal = (nextSnapshot, ordinal) => {
  const entry = nextSnapshot.value?.peerHistory?.controllerEvidence?.[String(ordinal)]
  return Array.isArray(entry?.roundStartFacilitators) ? entry.roundStartFacilitators : null
}

/**
 * Fetch a contiguous window of snapshots once, then evaluate every ordinal in it. Fetching per
 * ordinal inside the evaluation would triple the request count against a node that is also trying
 * to run consensus, and this whole scan sits inside a retry loop.
 *
 * NOTE: this is only safe because the dev rig disables the per-IP snapshot cap
 * (CL_SNAPSHOT_PER_IP_MAX_REQUESTS_PER_WINDOW=0 in docker-compose.test.yaml). Against a node
 * running the production default of 120 req/min the window fetch would start taking 429s.
 */
const scanWindow = async (globalL0Url) => {
  const latest = await fetchJson(`${globalL0Url}/global-snapshots/latest`)
  const head = latest.value.ordinal
  const from = Math.max(2, head - SCAN_DEPTH)
  const snapshots = new Map()
  for (let ordinal = from; ordinal <= head; ordinal++) {
    try {
      snapshots.set(ordinal, await fetchJson(`${globalL0Url}/global-snapshots/${ordinal}`))
    } catch (error) {
      // A single unavailable ordinal must not abort the scan; the window is large and the
      // remaining ordinals are just as good.
      logWorkflow.warning(`Could not fetch global snapshot ${ordinal}: ${error.message}`)
    }
  }

  let mixed = null
  let eventTriggered = null
  let bestSpread = null

  for (let ordinal = from + 1; ordinal < head; ordinal++) {
    const snapshot = snapshots.get(ordinal)
    const parent = snapshots.get(ordinal - 1)
    const next = snapshots.get(ordinal + 1)
    if (!snapshot || !parent || !next) continue

    const rewards = snapshot.value.rewards || []
    if (snapshot.value.epochProgress <= parent.value.epochProgress) {
      if (!eventTriggered) eventTriggered = { ordinal, rewards }
      continue
    }
    if (mixed || rewards.length === 0) continue

    const committee = committeeForOrdinal(next, ordinal)
    if (!committee) continue

    // The score window the legacy filter would have read for THIS ordinal.
    const scores = derivePeerScores(snapshot.value?.peerHistory?.controllerEvidence)
    const seatedScores = committee.map((peerId) => ({ peerId, score: scores.get(peerId) ?? 0 }))
    const below = seatedScores.filter((s) => s.score < PROMOTE_THRESHOLD)
    const atOrAbove = seatedScores.filter((s) => s.score >= PROMOTE_THRESHOLD)

    if (!bestSpread || below.length + atOrAbove.length > bestSpread.size) {
      bestSpread = {
        ordinal,
        size: committee.length,
        below: below.length,
        atOrAbove: atOrAbove.length,
      }
    }
    if (below.length > 0 && atOrAbove.length > 0) {
      mixed = {
        ordinal,
        rewards,
        committee,
        seatedScores,
        below,
        proofs: snapshot.proofs || [],
        delegateRewards: snapshot.value.delegateRewards,
      }
    }
  }

  return { head, mixed, eventTriggered, bestSpread }
}

const main = async () => {
  const args = process.argv.slice(2)
  const { dagL0PortPrefix, dagL1PortPrefix } = parseSharedArgs(args, false)
  const { globalL0Url } = createNetworkConfig({ dagL0PortPrefix, dagL1PortPrefix })
  const projectRoot = path.resolve(__dirname, '..', '..')

  logWorkflow.start('Committee reward distribution')
  logWorkflow.info(
    `gl0 nodes=${NUM_GL0} (early=${NUM_EARLY}, late=${NUM_GL0 - NUM_EARLY}), ` +
      `promote threshold=${PROMOTE_THRESHOLD}, scan depth=${SCAN_DEPTH}`,
  )

  const identities = loadNodeIdentities(projectRoot)
  const addressByPeerId = new Map(identities.map((i) => [i.peerId, i.address]))
  const nodeByPeerId = new Map(identities.map((i) => [i.peerId, i]))

  // Wait for a mixed committee: the late joiner must be seated AND still below the promote
  // threshold while the genesis peers are above it. Admissions are throttled to one certified
  // peer per expansion round and the late joiners only start climbing after their join delay,
  // so this legitimately takes several minutes.
  const {
    ordinal,
    rewards,
    committee,
    seatedScores,
    below,
    proofs,
    delegateRewards,
    eventTriggered,
  } = await withRetryOrdinal(
    async () => {
      const scan = await scanWindow(globalL0Url)
      if (!scan.mixed) {
        const seen = scan.bestSpread
          ? `best seen: ordinal ${scan.bestSpread.ordinal}, ${scan.bestSpread.size} seated ` +
            `(${scan.bestSpread.below} below promote, ${scan.bestSpread.atOrAbove} at/above)`
          : 'no TimeTrigger ordinal with rewards and landed evidence yet'
        throw new Error(
          `no TimeTrigger ordinal below head ${scan.head} has a MIXED committee ` +
            `(at least one facilitator below the promote threshold and one at/above); ${seen}`,
        )
      }
      return { ...scan.mixed, eventTriggered: scan.eventTriggered }
    },
    {
      globalL0Url,
      name: 'TimeTrigger ordinal with a mixed-score committee',
      maxOrdinalMisses: 60,
      maxStalledChecks: 30,
      interval: 5000,
    },
  )

  const rewardedAddresses = new Set(rewards.map((r) => r.destination))
  const describe = (peerId) => {
    const node = nodeByPeerId.get(peerId)
    return node ? `gl0-${node.index}` : peerId.slice(0, 12)
  }

  logWorkflow.info(
    `Sampled TimeTrigger ordinal ${ordinal}: committee=${committee.length}, rewards=${rewards.length}, ` +
      `scores=[${seatedScores.map((s) => `${describe(s.peerId)}:${s.score}`).join(' ')}]`,
  )

  // Precondition: no delegated stakes. With active stakes the distributor also emits per-operator
  // dynamic commission rewards into the same `rewards` array, which are NOT an equal split and
  // are addressed to the operator's declared source rather than its peer address. That would fail
  // the equality assertion below for a reason unrelated to this test.
  if (delegateRewards && Object.keys(delegateRewards).length > 0) {
    throw new Error(
      `Ordinal ${ordinal} carries delegator rewards for ${Object.keys(delegateRewards).length} operator(s), ` +
        `so active delegated stakes exist and per-operator commission rewards are mixed into the reward ` +
        `set. Run committee-rewards on a cluster with no delegated stakes (do not combine it with the ` +
        `delegated-staking suite).`,
    )
  }

  // 1. Reward destinations are EXACTLY the frozen committee. This is the whole property: no
  //    seated member filtered out, no non-seated address paid.
  const expectedAddresses = new Set(
    committee.map((peerId) => {
      const address = addressByPeerId.get(peerId)
      if (!address) {
        throw new Error(
          `Committee member ${peerId.slice(0, 12)} at ordinal ${ordinal} is not a known gl0 node; ` +
            `the nodes/<i>/peer_id map is stale or the cluster has unexpected peers`,
        )
      }
      return address
    }),
  )
  const missing = [...expectedAddresses].filter((a) => !rewardedAddresses.has(a))
  const unexpected = [...rewardedAddresses].filter((a) => !expectedAddresses.has(a))
  if (missing.length > 0 || unexpected.length > 0) {
    throw new Error(
      `Reward destinations at ordinal ${ordinal} do not match the frozen committee. ` +
        `Missing (seated but unpaid): ${JSON.stringify(missing)}. ` +
        `Unexpected (paid but not seated): ${JSON.stringify(unexpected)}.`,
    )
  }

  // 2. THE REGRESSION ASSERTION. Every below-promote-threshold member of a mixed committee is
  //    paid. Under the pre-#1547 evidence-score filter these were dropped while their
  //    saturated-score peers were paid.
  for (const climber of below) {
    const address = addressByPeerId.get(climber.peerId)
    if (!rewardedAddresses.has(address)) {
      throw new Error(
        `${describe(climber.peerId)} was seated at ordinal ${ordinal} with score ${climber.score} ` +
          `(below promote threshold ${PROMOTE_THRESHOLD}) but received no reward -- this is the ` +
          `evidence-score payout filter regression`,
      )
    }
  }

  // 3. Equal split. The pool is divided and rounded ONCE upstream, so this is exact, not a band.
  const amounts = new Set(rewards.map((r) => r.amount))
  if (amounts.size !== 1) {
    throw new Error(
      `Committee members received unequal rewards at ordinal ${ordinal}: ${JSON.stringify(
        rewards.map((r) => ({ destination: r.destination, amount: r.amount })),
      )}. If this run created delegated stakes, per-operator commission rewards are mixed in and ` +
        `this test must run on a cluster without them.`,
    )
  }
  const [amount] = [...amounts]
  if (!(amount > 0)) {
    throw new Error(`Committee reward amount at ordinal ${ordinal} was not positive: ${amount}`)
  }

  // 4. Signers are a subset of the paid committee: payout follows SEATING, not who happened to get
  //    a signature in before finalization.
  const signersOutsideCommittee = (proofs || []).map((p) => p.id).filter((id) => !committee.includes(id))
  if (signersOutsideCommittee.length > 0) {
    throw new Error(
      `Ordinal ${ordinal} was signed by peers outside the frozen committee: ` +
        `${JSON.stringify(signersOutsideCommittee.map(describe))}`,
    )
  }

  // 5. EventTrigger control: static validator rewards mint on TimeTrigger rounds only.
  if (eventTriggered && eventTriggered.rewards.length > 0) {
    throw new Error(
      `EventTrigger ordinal ${eventTriggered.ordinal} minted ${eventTriggered.rewards.length} reward(s); ` +
        `static validator rewards must only mint on TimeTrigger rounds`,
    )
  }

  logWorkflow.info(
    `Ordinal ${ordinal}: all ${committee.length} seated facilitators paid ${amount} datum each, ` +
      `including ${below.length} below the promote threshold ` +
      `(${below.map((c) => `${describe(c.peerId)}:${c.score}`).join(' ')})`,
  )
  if (eventTriggered) {
    logWorkflow.info(`EventTrigger control ordinal ${eventTriggered.ordinal}: 0 rewards, as expected`)
  }
  logWorkflow.success('Committee reward distribution')
}

main()
  .then(() => process.exit(0))
  .catch((error) => {
    logWorkflow.error('Committee reward distribution', error.message)
    process.exit(1)
  })
