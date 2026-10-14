#!/usr/bin/env node
/**
 * committee_rewards.js -- delegated validator rewards must pay the FULL frozen committee.
 *
 * WHAT THIS PROTECTS (ADR-0028, ADR-0036)
 * Global L0 has one frozen voting set per round (roundStartFacilitators). Static validator rewards
 * are split equally across that whole set: a seated member is paid whether or not its signature
 * made it into the artifact proofs, and a member admitted mid-life is paid from its first seated
 * round. The regressions this catches are payout following signers instead of seats, and payout
 * excluding newly admitted members.
 *
 * THE SAMPLE
 * A TimeTrigger ordinal whose frozen committee contains at least one late joiner (admitted through
 * open admission after genesis) alongside genesis peers. If some seated member did not sign that
 * ordinal, the sample also exercises "paid by seat, not by signature"; that case is reported but
 * not required, because a healthy rig often collects every signature inside the grace window.
 *
 * THE RIG (docker/bin/set-env.sh NUM_GL0_EARLY)
 * 5 gl0 nodes: 3 join at genesis, 2 delay their self-join. Requires
 * --num-gl0=5 --num-gl0-early=3.
 *
 * ORACLE (signed, consensus-agreed)
 *   committee(N) = snapshot[N+1].value.certifiedLineage.parentOutcome.proposalQc.value.roundStartFacilitators
 * Snapshot N+1 embeds the certified outcome of round N, whose ProposalValue carries the frozen
 * committee that voted on N.
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

const NUM_GL0 = parseInt(process.env.NUM_GL0_NODES || '5', 10)
const NUM_EARLY = parseInt(process.env.NUM_GL0_EARLY || '3', 10)
// Ordinals below the head to search. The window is fetched once per attempt.
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

const committeeForOrdinal = (nextSnapshot) => {
  const value = nextSnapshot.value?.certifiedLineage?.parentOutcome?.proposalQc?.value
  return Array.isArray(value?.roundStartFacilitators) ? value : null
}

/**
 * Fetch a contiguous window of snapshots once, then evaluate every ordinal in it. Fetching per
 * ordinal inside the evaluation would triple the request count against a node that is also trying
 * to run consensus, and this whole scan sits inside a retry loop.
 *
 * NOTE: this is only safe because the dev rig disables the per-IP snapshot cap
 * (CL_SNAPSHOT_PER_IP_MAX_REQUESTS_PER_WINDOW=0 in docker-compose.test.yaml). A packaged dag-l0
 * node caps it at 120 req/min, so pointing this scan at a real network would start taking 429s.
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
      // A single unavailable ordinal must not abort the scan.
      logWorkflow.warning(`Could not fetch global snapshot ${ordinal}: ${error.message}`)
    }
  }

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
    if (rewards.length === 0) continue

    const certified = committeeForOrdinal(next)
    if (!certified || certified.key !== ordinal) continue
    const committee = certified.roundStartFacilitators
    const lateSeated = committee.filter((peerId) => lateJoinerIds.has(peerId))
    const earlySeated = committee.filter((peerId) => !lateJoinerIds.has(peerId))
    const signerIds = new Set((snapshot.proofs || []).map((p) => p.id))
    const seatedNonSigners = committee.filter((peerId) => !signerIds.has(peerId))

    if (!best || lateSeated.length > best.lateSeated) {
      best = { ordinal, size: committee.length, lateSeated: lateSeated.length }
    }
    if (lateSeated.length === 0 || earlySeated.length === 0) continue

    // Prefer a sample that also has a seated non-signer; keep the first plain sample otherwise.
    if (!sample || (sample.seatedNonSigners.length === 0 && seatedNonSigners.length > 0)) {
      sample = {
        ordinal,
        rewards,
        committee,
        lateSeated,
        seatedNonSigners,
        proofs: snapshot.proofs || [],
        delegateRewards: snapshot.value.delegateRewards,
      }
    }
  }

  return { head, sample, eventTriggered, best }
}

const main = async () => {
  const args = process.argv.slice(2)
  const { dagL0PortPrefix, dagL1PortPrefix } = parseSharedArgs(args, false)
  const { globalL0Url } = createNetworkConfig({ dagL0PortPrefix, dagL1PortPrefix })
  const projectRoot = path.resolve(__dirname, '..', '..')

  logWorkflow.start('Committee reward distribution')
  logWorkflow.info(
    `gl0 nodes=${NUM_GL0} (early=${NUM_EARLY}, late=${NUM_GL0 - NUM_EARLY}), ` +
      `scan depth=${SCAN_DEPTH}`,
  )

  const identities = loadNodeIdentities(projectRoot)
  const addressByPeerId = new Map(identities.map((i) => [i.peerId, i.address]))
  const nodeByPeerId = new Map(identities.map((i) => [i.peerId, i]))
  const lateJoinerIds = new Set(identities.filter((i) => i.isLateJoiner).map((i) => i.peerId))
  if (lateJoinerIds.size === 0) {
    throw new Error(
      `no late joiners configured (NUM_GL0_EARLY=${NUM_EARLY} of NUM_GL0_NODES=${NUM_GL0}); ` +
        `the test needs an admitted member in the committee`,
    )
  }

  // Wait until a late joiner has been admitted and is seated on a TimeTrigger round. Admissions are
  // throttled to one certified peer per expansion round and the late joiners only join after their
  // delay, so this legitimately takes several minutes.
  const { ordinal, rewards, committee, lateSeated, seatedNonSigners, proofs, delegateRewards, eventTriggered } =
    await withRetryOrdinal(
      async () => {
        const scan = await scanWindow(globalL0Url, lateJoinerIds)
        if (!scan.sample) {
          const seen = scan.best
            ? `best seen: ordinal ${scan.best.ordinal}, ${scan.best.size} seated, ${scan.best.lateSeated} late joiner(s)`
            : 'no TimeTrigger ordinal with rewards and a certified successor yet'
          throw new Error(
            `no TimeTrigger ordinal below head ${scan.head} has a late joiner seated next to genesis peers; ${seen}`,
          )
        }
        return { ...scan.sample, eventTriggered: scan.eventTriggered }
      },
      {
        globalL0Url,
        name: 'TimeTrigger ordinal with an admitted member seated',
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
      `late joiners seated=[${lateSeated.map(describe).join(' ')}], ` +
      `seated non-signers=[${seatedNonSigners.map(describe).join(' ')}]`,
  )
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

  // 1. Every admitted member seated this round is paid, and so is every seated member whose
  //    signature did not make it into the proofs. Both are covered by assertion 2; they are checked
  //    first so a failure names the regression directly.
  for (const peerId of [...lateSeated, ...seatedNonSigners]) {
    if (!rewardedNodeAddresses.has(addressByPeerId.get(peerId))) {
      const why = lateSeated.includes(peerId) ? 'admitted member' : 'member without a proof signature'
      throw new Error(
        `${describe(peerId)} was a seated ${why} at ordinal ${ordinal} but received no reward -- ` +
          `payout must follow the frozen committee`,
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
      `including ${lateSeated.length} admitted member(s) and ${seatedNonSigners.length} seated ` +
      `non-signer(s)`,
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
