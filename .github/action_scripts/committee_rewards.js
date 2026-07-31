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
 * below the promote threshold and at least one at or above it.
 *
 * AND IT MUST BE A REAL TIER-1 SEAT IN THE FINAL COMMITTEE
 * "Below promote" is not a tier claim, and neither is the evidence-derived tier on its own.
 * `ControllerEvidenceDerivation.derive` gives
 *     tier = if (!windowDeepEnough || signedRecently) Core else Tier1
 * but `CommitteeBuilder` can then PROMOTE a derived-Tier-1 peer back into Core: both the
 * one-for-one replacement step and the Core-floor top-up draw from
 *     corePromotablePool = rawTier1.filterNot(isChronic || nonCorePeers)
 * so derivation alone would let an all-Core committee satisfy this test and miss a future
 * Core-only payout regression.
 *
 * The predicate therefore requires the candidate to be CHRONIC (a trailing seated-but-missed streak
 * >= ChronicMissThreshold), which bars it from BOTH promotion mechanisms, AND at least
 * MinViableCoreSize healthy Core members to remain, which zeroes the step-5 liveness fallback -- the
 * only remaining path that re-admits a chronic peer. Both are computed from signed evidence the way
 * the builder computes them. See the scan for the full argument.
 *
 * Two superseded approaches, recorded so they are not retried: (1) "below retain, therefore
 * probation-admitted" is sound but its sample is unreachable -- on a 5-node rig genesis scores dip
 * below retain from ordinary missed rounds exactly while a climber is below retain, so the two
 * conditions are anti-correlated (0 qualifying ordinals in 70 measured live). (2) derivation alone,
 * without the chronic + fallback conditions, is unsound for the promotion reason above.
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
// Reported in diagnostics only: retain is where a peer re-enters the sticky recent-signer pool, so
// it explains WHY a seat is still churning, but it is not the Tier-1 discriminator (see header).
const RETAIN_THRESHOLD = parseInt(process.env.CL_ACTIVE_ADMISSION_RETAIN_THRESHOLD || '70', 10)
// Lookback depth of the recent-signer pool (dev `active-admission-recent-signer-window`), floored
// internally by the node to DemotionConsecutiveMisses.
const RECENT_SIGNER_WINDOW = parseInt(process.env.CL_ACTIVE_ADMISSION_RECENT_SIGNER_WINDOW || '10', 10)
// TierTransitions.DemotionConsecutiveMisses -- the compiled-in depth at which the recent-signer
// window is considered deep enough to arm the filter at all.
const DEMOTION_CONSECUTIVE_MISSES = 3
// ControllerEvidenceDerivation.ChronicMissThreshold (== DemotionConsecutiveMisses): a trailing
// seated-but-missed streak this long makes a peer chronic, which bars it from BOTH Core-floor
// promotion and one-for-one replacement in CommitteeBuilder.
const CHRONIC_MISS_THRESHOLD = 3
// CommitteeBuilder.MinViableCoreSize: below this many healthy Core members the step-5 liveness
// fallback re-admits chronic peers into Core. At or above it, the fallback takes zero.
const MIN_VIABLE_CORE_SIZE = 2
// CommitteeBuilder quality gate inputs (dag-l0.conf min-participation-observations / -ratio).
const MIN_PARTICIPATION_OBSERVATIONS = parseInt(process.env.CL_MIN_PARTICIPATION_OBSERVATIONS || '10', 10)
const MIN_PARTICIPATION_RATIO = parseFloat(process.env.CL_MIN_PARTICIPATION_RATIO || '0.5')

const NUM_GL0 = parseInt(process.env.NUM_GL0_NODES || '5', 10)
const NUM_EARLY = parseInt(process.env.NUM_GL0_EARLY || '3', 10)
// Ordinals below the head to search. Qualifying states cluster in the committee's GROWTH phase and
// stop recurring once every peer is saturated and signing, so a short tail-window scan starts
// missing them as the cluster matures: observed live at head ~209, a 60-ordinal scan found nothing
// while a 200-ordinal scan immediately found ordinal 29. Default generously; the window is fetched
// once per attempt.
const SCAN_DEPTH = parseInt(process.env.COMMITTEE_REWARDS_SCAN_DEPTH || '200', 10)

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

/**
 * The recent-signer window as the admission filter sees it: the last `effectiveRecentSignerWindow`
 * entries of the signed `peerHistory.recentSigners` map, flattened to a membership set, plus the
 * entry count for the `recentWindowDeepEnough` guard. Keys are decimal ordinals, so sort numerically
 * rather than lexically (otherwise "9" sorts after "10").
 */
/**
 * `ControllerEvidenceDerivation.consecutiveMisses`: trailing entries where the peer was seated
 * (in roundStartFacilitators) but did not sign, counted back from the most recent entry. An entry
 * where the peer SIGNED resets the streak; an entry where it was NOT seated BREAKS it. A peer whose
 * streak reaches ChronicMissThreshold is chronic, which is what bars it from Core-floor promotion.
 */
const consecutiveMisses = (controllerEvidence, peerId) => {
  const ordinals = Object.keys(controllerEvidence || {})
    .map((k) => parseInt(k, 10))
    .sort((a, b) => a - b)
  let streak = 0
  for (let i = ordinals.length - 1; i >= 0; i--) {
    const entry = controllerEvidence[String(ordinals[i])]
    const seated = (entry.roundStartFacilitators || []).includes(peerId)
    const signed = (entry.completedSigners || []).includes(peerId)
    if (seated && !signed) streak++
    else break
  }
  return streak
}

/** `peerQuality` as the builder derives it: (completed, participated) over the evidence window. */
const peerQuality = (controllerEvidence, peerId) => {
  const entries = Object.values(controllerEvidence || {})
  let completed = 0
  let participated = 0
  for (const entry of entries) {
    if ((entry.roundStartFacilitators || []).includes(peerId)) participated++
    if ((entry.completedSigners || []).includes(peerId)) completed++
  }
  return { completed, participated }
}

/** CommitteeBuilder.isQualityDegraded: enough observations AND ratio below the minimum. */
const isQualityDegraded = (controllerEvidence, peerId) => {
  const { completed, participated } = peerQuality(controllerEvidence, peerId)
  return participated >= MIN_PARTICIPATION_OBSERVATIONS && completed / participated < MIN_PARTICIPATION_RATIO
}

const recentSignerSets = (recentSigners) => {
  const ordinals = Object.keys(recentSigners || {})
    .map((k) => parseInt(k, 10))
    .sort((a, b) => a - b)
  // Mirror ActiveFacilitatorAdmission's floor: `effectiveRecentSignerWindow =
  // math.max(DemotionConsecutiveMisses, recentSignerWindow)`. Slicing by the raw configured value
  // would diverge for an override below 3 -- the node would look at 3 entries while this test looked
  // at 1 or 2 and wrongly declare the gate unarmed.
  const effectiveRecentSignerWindow = Math.max(DEMOTION_CONSECUTIVE_MISSES, RECENT_SIGNER_WINDOW)
  const windowed = ordinals.slice(-effectiveRecentSignerWindow)
  const signers = new Set()
  for (const ordinal of windowed) {
    for (const peerId of recentSigners[String(ordinal)] || []) signers.add(peerId)
  }
  // The tier derivation uses a NARROWER window than the admission pool: `derive` takes the last
  // DemotionConsecutiveMisses entries, while the pool takes effectiveRecentSignerWindow. Both are
  // returned so each consumer uses the one the node uses.
  const recent = ordinals.slice(-DEMOTION_CONSECUTIVE_MISSES)
  const recentSignersSet = new Set()
  for (const ordinal of recent) {
    for (const peerId of recentSigners[String(ordinal)] || []) recentSignersSet.add(peerId)
  }
  return { entryCount: windowed.length, signers, recentSigners: recentSignersSet }
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
 * (CL_SNAPSHOT_PER_IP_MAX_REQUESTS_PER_WINDOW=0 in docker-compose.test.yaml). A packaged dag-l0
 * node caps it at 120 req/min (dag-l0.conf `per-ip-max-requests-per-window`, which overrides the
 * shared default of 0), so pointing this scan at a real network would start taking 429s.
 */
const scanWindow = async (globalL0Url, lateJoinerIds) => {
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

  // ANCHOR: the evidence ordinal at which each late joiner was certified into the facilitator
  // base. Rather than hunting a transient score window anywhere in recent history, key off the
  // admission itself -- the climb begins there, so the Tier-1 rounds are the handful immediately
  // after. `admittedPeers` is part of the signed evidence entry, so this is consensus-agreed.
  // An admission recorded FOR round A first affects the round-start committee at A+1, so a peer
  // only counts as a probation climber on reward ordinals strictly greater than its anchor.
  const admissionOrdinalByPeer = new Map()
  for (let ordinal = from; ordinal <= head; ordinal++) {
    const next = snapshots.get(ordinal + 1)
    const entry = next?.value?.peerHistory?.controllerEvidence?.[String(ordinal)]
    for (const peerId of entry?.admittedPeers || []) {
      if (lateJoinerIds.has(peerId) && !admissionOrdinalByPeer.has(peerId)) {
        admissionOrdinalByPeer.set(peerId, ordinal)
      }
    }
  }
  const anchor = [...admissionOrdinalByPeer.entries()]
    .map(([peerId, ordinal]) => ({ peerId, ordinal }))
    .sort((a, b) => a.ordinal - b.ordinal)[0]

  let sample = null
  let eventTriggered = null
  let best = null

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
    if (sample || rewards.length === 0) continue

    const committee = committeeForOrdinal(next, ordinal)
    if (!committee) continue

    // The score window the legacy filter would have read for THIS ordinal.
    const scores = derivePeerScores(snapshot.value?.peerHistory?.controllerEvidence)
    const seatedScores = committee.map((peerId) => ({ peerId, score: scores.get(peerId) ?? 0 }))

    // TIER-1 PROOF. Derivation alone is NOT sufficient: a peer absent from the last
    // DemotionConsecutiveMisses signer sets derives Tier 1, but `CommitteeBuilder` can then PROMOTE
    // it back into Core -- step 2 (one-for-one replacement of chronic Core members) and step 3 (the
    // Core-floor top-up) both draw from
    //     corePromotablePool = rawTier1.filterNot(isChronic || nonCorePeers)
    // so a derived-Tier-1 peer that is neither chronic nor on probation can end the round as Core.
    // Asserting Tier 1 from derivation alone would let an all-Core committee pass and would miss a
    // future Core-only payout regression (codex round 5).
    //
    // Two conditions close that hole, both computable from signed evidence:
    //   (a) the candidate is CHRONIC -- consecutiveMisses >= ChronicMissThreshold -- which bars it
    //       from BOTH promotion mechanisms ("chronic peers are categorically barred from BOTH");
    //   (b) at least MinViableCoreSize healthy Core members remain, so the step-5 liveness fallback
    //       (the only path that re-admits a chronic peer) takes zero:
    //           readmitted = (...).take(max(0, readmitTarget - healthySize)),
    //           readmitTarget = min(MinViableCoreSize, max(coreFloor, rawCore.size)).
    // A "healthy Core member" here is a committee peer that signed within the last
    // DemotionConsecutiveMisses sets (so it derives Core), is not chronic, is not quality-degraded,
    // and scores at/above retain (so it sits in the recent-signer pool rather than probation, i.e.
    // it is not in nonCorePeers). Each of those is the builder's own test, computed the builder's way.
    const evidence = snapshot.value?.peerHistory?.controllerEvidence
    const recentWindow = recentSignerSets(snapshot.value?.peerHistory?.recentSigners)
    const windowDeepEnough = recentWindow.entryCount >= DEMOTION_CONSECUTIVE_MISSES

    const healthyCore = seatedScores.filter(
      (s) =>
        recentWindow.recentSigners.has(s.peerId) &&
        consecutiveMisses(evidence, s.peerId) < CHRONIC_MISS_THRESHOLD &&
        !isQualityDegraded(evidence, s.peerId) &&
        s.score >= RETAIN_THRESHOLD,
    )
    const fallbackBlocked = healthyCore.length >= MIN_VIABLE_CORE_SIZE

    const tier1 = windowDeepEnough
      ? seatedScores.filter(
          (s) =>
            !recentWindow.recentSigners.has(s.peerId) &&
            consecutiveMisses(evidence, s.peerId) >= CHRONIC_MISS_THRESHOLD,
        )
      : []
    // Only a Tier-1 seat BELOW the promote threshold is discriminating: that is precisely what the
    // removed filter dropped.
    const tier1BelowPromote = fallbackBlocked ? tier1.filter((s) => s.score < PROMOTE_THRESHOLD) : []
    const belowPromote = seatedScores.filter((s) => s.score < PROMOTE_THRESHOLD)
    const atOrAbovePromote = seatedScores.filter((s) => s.score >= PROMOTE_THRESHOLD)

    if (!best || tier1BelowPromote.length > best.tier1) {
      best = {
        ordinal,
        size: committee.length,
        tier1: tier1BelowPromote.length,
        chronicTier1: tier1.length,
        healthyCore: healthyCore.length,
        fallbackBlocked,
        windowDeepEnough,
        windowEntries: recentWindow.entryCount,
        belowPromote: belowPromote.length,
        atOrAbovePromote: atOrAbovePromote.length,
      }
    }

    // The legacy filter only differed from full-committee payout when SOME facilitator qualified
    // and some did not (it paid everyone when nobody qualified).
    const legacyWouldHaveFiltered = tier1BelowPromote.length > 0 && atOrAbovePromote.length > 0
    if (legacyWouldHaveFiltered) {
      sample = {
        ordinal,
        rewards,
        committee,
        seatedScores,
        tier1: tier1BelowPromote,
        belowPromote,
        proofs: snapshot.proofs || [],
        delegateRewards: snapshot.value.delegateRewards,
      }
    }
  }

  return { head, anchor, sample, eventTriggered, best }
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
  const lateJoinerIds = new Set(identities.filter((i) => i.isLateJoiner).map((i) => i.peerId))
  if (lateJoinerIds.size === 0) {
    throw new Error(
      `no late joiners configured (NUM_GL0_EARLY=${NUM_EARLY} of NUM_GL0_NODES=${NUM_GL0}); ` +
        `the rig is far less likely to produce a chronic Tier-1 seat`,
    )
  }

  // Wait for a mixed committee: the late joiner must be seated AND still below the promote
  // threshold while the genesis peers are above it. Admissions are throttled to one certified
  // peer per expansion round and the late joiners only start climbing after their join delay,
  // so this legitimately takes several minutes.
  const {
    ordinal,
    rewards,
    committee,
    seatedScores,
    tier1,
    belowPromote,
    proofs,
    delegateRewards,
    anchor,
    eventTriggered,
  } = await withRetryOrdinal(
    async () => {
      const scan = await scanWindow(globalL0Url, lateJoinerIds)
      // The admission anchor is now diagnostic context rather than a gate: the Tier-1 proof no
      // longer depends on which lane seated the peer, and a genesis peer that went quiet is just as
      // genuinely Tier 1 as a fresh climber.
      if (!scan.sample) {
        const seen = scan.best
          ? `best seen: ordinal ${scan.best.ordinal}, ${scan.best.size} seated ` +
            `(${scan.best.tier1} Tier-1 seats below promote, windowDeepEnough=` +
            `${scan.best.windowDeepEnough} from ${scan.best.windowEntries} window entries, ` +
            `${scan.best.belowPromote} below promote, ${scan.best.atOrAbovePromote} at/above promote)`
          : 'no TimeTrigger ordinal with rewards and landed evidence yet'
        throw new Error(
          `no TimeTrigger ordinal below head ${scan.head} carries a seated Tier-1 facilitator ` +
            `(absent from the last ${DEMOTION_CONSECUTIVE_MISSES} signer sets) that is below the ` +
            `promote threshold, alongside a promote-qualified peer; ${seen}`,
        )
      }
      return { ...scan.sample, anchor: scan.anchor, eventTriggered: scan.eventTriggered }
    },
    {
      globalL0Url,
      name: 'TimeTrigger ordinal with a seated Tier-1 facilitator below promote',
      maxOrdinalMisses: 60,
      maxStalledChecks: 30,
      interval: 5000,
    },
  )

  const describe = (peerId) => {
    const node = nodeByPeerId.get(peerId)
    return node ? `gl0-${node.index}` : peerId.slice(0, 12)
  }

  // Partition the reward set. `snapshot.rewards` is a UNION of validator (static + dynamic
  // commission), reserved-address, one-time, and delegated-withdrawal payouts
  // (GlobalSnapshotAcceptanceManager unions them before acceptance). Only the entries addressed to
  // a known gl0 node key are the equal-split validator rewards this test is about; anything else
  // is a different reward class and must be ignored rather than treated as an unexpected payout.
  const knownNodeAddresses = new Set(identities.map((i) => i.address))
  const validatorRewards = rewards.filter((r) => knownNodeAddresses.has(r.destination))
  const otherRewards = rewards.filter((r) => !knownNodeAddresses.has(r.destination))
  const rewardedNodeAddresses = new Set(validatorRewards.map((r) => r.destination))

  logWorkflow.info(
    `Sampled TimeTrigger ordinal ${ordinal}: committee=${committee.length}, ` +
      `validator rewards=${validatorRewards.length}, other reward classes=${otherRewards.length}, ` +
      `scores=[${seatedScores.map((s) => `${describe(s.peerId)}:${s.score}`).join(' ')}]`,
  )
  if (anchor) {
    logWorkflow.info(
      `Earliest late-joiner admission in the window: ${describe(anchor.peerId)} at ordinal ${anchor.ordinal}`,
    )
  }
  if (otherRewards.length > 0) {
    logWorkflow.info(
      `Ignoring ${otherRewards.length} non-validator reward(s): ` +
        `${JSON.stringify(otherRewards.map((r) => r.destination))}`,
    )
  }

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

  // 1. THE REGRESSION ASSERTION, and the Tier-1 proof. `tier1` holds seated members that carry
  //    a chronic trailing-miss streak, which bars Core-floor promotion, while >= MinViableCoreSize
  //    healthy Core members block the liveness fallback that is the only remaining path back into
  //    Core. See the scan for the full argument.
  //    Under the pre-#1547 filter these Tier-1 seats were dropped from the payout while their
  //    promote-qualified peers were paid.
  for (const climber of tier1) {
    const address = addressByPeerId.get(climber.peerId)
    if (!rewardedNodeAddresses.has(address)) {
      throw new Error(
        `${describe(climber.peerId)} was a seated Tier-1 facilitator at ordinal ${ordinal} with score ` +
          `${climber.score} (absent from the last ${DEMOTION_CONSECUTIVE_MISSES} signer sets, therefore ` +
          `derived non-Core) but received no reward -- this is the evidence-score payout filter regression`,
      )
    }
  }

  // 2. Reward destinations are EXACTLY the frozen committee. This is the whole property: no
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
  const missing = [...expectedAddresses].filter((a) => !rewardedNodeAddresses.has(a))
  const unexpected = [...rewardedNodeAddresses].filter((a) => !expectedAddresses.has(a))
  if (missing.length > 0 || unexpected.length > 0) {
    throw new Error(
      `Validator reward destinations at ordinal ${ordinal} do not match the frozen committee. ` +
        `Missing (seated but unpaid): ${JSON.stringify(missing.map((a) => a))}. ` +
        `Unexpected (paid but not seated): ${JSON.stringify(unexpected)}.`,
    )
  }

  // 3. Equal split across validator rewards. The pool is divided and rounded ONCE upstream, so
  //    this is exact, not a band. Non-validator reward classes are excluded above.
  const amounts = new Set(validatorRewards.map((r) => r.amount))
  if (amounts.size !== 1) {
    throw new Error(
      `Committee members received unequal validator rewards at ordinal ${ordinal}: ${JSON.stringify(
        validatorRewards.map((r) => ({ destination: r.destination, amount: r.amount })),
      )}. If this run created delegated stakes, per-operator commission rewards addressed to a node ` +
        `key would land here; run committee-rewards without the delegated-staking suite.`,
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

  // 5. EventTrigger control: static validator rewards mint on TimeTrigger rounds only. Compared
  //    against known node addresses for the same reason as above -- an unrelated reward class on
  //    an EventTrigger round is not this test's business.
  const eventValidatorRewards = (eventTriggered?.rewards || []).filter((r) =>
    knownNodeAddresses.has(r.destination),
  )
  if (eventValidatorRewards.length > 0) {
    throw new Error(
      `EventTrigger ordinal ${eventTriggered.ordinal} minted ${eventValidatorRewards.length} validator ` +
        `reward(s); static validator rewards must only mint on TimeTrigger rounds`,
    )
  }

  logWorkflow.info(
    `Ordinal ${ordinal}: all ${committee.length} seated facilitators paid ${amount} datum each, ` +
      `including ${tier1.length} Tier-1 seat(s) absent from the last ${DEMOTION_CONSECUTIVE_MISSES} ` +
      `signer sets ` +
      `(${tier1.map((c) => `${describe(c.peerId)}:${c.score}`).join(' ')}) and ` +
      `${belowPromote.length} below the promote threshold the legacy filter would have dropped`,
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
