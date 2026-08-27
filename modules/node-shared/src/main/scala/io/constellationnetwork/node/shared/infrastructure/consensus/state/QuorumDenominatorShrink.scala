package io.constellationnetwork.node.shared.infrastructure.consensus.state

import scala.collection.immutable.SortedSet

import io.constellationnetwork.node.shared.infrastructure.consensus.ViewFromTime
import io.constellationnetwork.schema.peer.PeerId

/** Escalating deterministic quorum-denominator shrink: the structural liveness rung for the "mathematically wedged" committee class (task
  * #123 / the Apr-29 deadlock / the live ord-3150197 wedge).
  *
  * ==The wedge it targets==
  *
  * A post-restart committee is seeded from the last snapshot (e.g. 6 members). Some members are gossip-responsive but consensus-dead
  * (divergent state, facilities rejected, silent leader). Quorum is `supermajority(coreSize)` (4-of-6). EVERY certificate family --
  * facility/proposal/signature phase quorums, view-change certificates, timeout certificates -- requires that same 4-of-6, so the healthy
  * minority can neither rotate the leader, nor advance views, nor close a round. No round closes, so no new `controllerEvidence` is ever
  * recorded, so the evidence-driven chronic-replacement ladder (which WOULD rebuild the committee around the healthy peers) never gets the
  * miss evidence it needs. The cluster is wedged by construction, forever.
  *
  * This rung lets the healthy subset close ONE round at a reduced quorum requirement. The persisted committee (`lastOutcome` facilitators,
  * `roundStartFacilitators`, `facilitatorsHash`) is NEVER touched: the shrink applies only to the quorum DENOMINATOR used for cert/phase
  * feasibility at the stuck key. Once the shrunken round closes, the chain advances, the evidence window records the dead peers' misses,
  * and the normal chronic-replacement machinery takes over from the next round-start derivation.
  *
  * ==The determinism trap (read before changing ANY input)==
  *
  * Committee/quorum decisions derived from LOCAL observations (gossip responsiveness, locally-seen declarations, local retry counters)
  * diverge across nodes and have repeatedly forked `facilitatorsHash` (alpha.92 / 129 / 147 -- see
  * `.workspace/controller-evidence-design.md`). Every input to this rung is therefore either consensus-agreed data or a monotone shared
  * anchor:
  *
  *   - '''Anchor set''' (`anchor`): the most recent `controllerEvidence` entry's `completedSigners` intersected with
  *     `roundStartFacilitators`. `completedSigners` is canonical signed-outcome data (see
  *     `ControllerEvidenceDerivation.canonicalCompletedSigners` -- proposal-carried responders, never locally-accreting proofs);
  *     `roundStartFacilitators` is the frozen round committee, and every Facility/MajoritySignature binds `facilitatorsHash`, so a node
  *     holding a different committee cannot participate in the round at all. Two-node argument: node A with 10 extra locally-received
  *     declarations and node B with none hold byte-identical `lastOutcome` (it is the signed previous outcome) and byte-identical frozen
  *     committees, hence compute identical anchors. We deliberately do NOT anchor on the round's accepted Facility set: facility
  *     declarations accrete in node-local arrival order, so two honest nodes legitimately hold different facility sets mid-round.
  *   - '''Escalation steps''' (`escalationSteps`): wall-clock progress since the parent outcome's `consensusEndTime`
  *     (`lastOutcome.recentRoundEndTimes` last entry), measured in `viewInterval` units via [[ViewFromTime]] -- the v19 phase-2 anchor the
  *     timestamp pacemaker already uses. The parent end time is facility-median outcome data shared by all nodes that finalized the parent
  *     round (bounded divergence: medians over the same round's proposer clocks differ by at most seconds across quorum-crossing races,
  *     versus a 60s `viewInterval` granularity and an activation threshold of several intervals). The local wall clock is the ONLY local
  *     input; it is monotone, so two honest nodes disagree on `steps` only within (clock skew + median divergence) of a step boundary,
  *     transiently, and one-directionally -- the stricter node accepts strictly LATER, never accepts a DIFFERENT value. Every consumer
  *     re-evaluates the gate periodically (monitor ticks, CheckUpdate re-validation), so a transient strict/relaxed split self-heals within
  *     the skew bound. The result never feeds `facilitatorsHash`, committee derivation, leader selection, or signed bytes.
  *   - '''Config''' (`quorumShrinkActivationViews`, `viewInterval`, `quorumThresholdFraction`): all folded into `deterministicConfigHash`,
  *     so divergent operator values handshake-reject at Facility exchange instead of silently forking.
  *
  * We deliberately do NOT use the local `AbandonmentTracker` counters as an acceptance input: they are node-local `Ref`s that reset on
  * restart and advance at node-local cadence (the alpha.104 lesson -- they must never seed consensus-critical state). One abandonment cycle
  * is ~1 `viewInterval` of silence, so the time anchor is the deterministic analog of "K consecutive abandonments at the same key" while
  * being byte-comparable across nodes.
  *
  * ==Escalation and floor==
  *
  * With `q0 = max(1, QuorumPolicy.fromFraction(coreSize, fraction))` and `a = anchor.size`:
  *
  *   - steps `<= 0` (silence shorter than `activationViews * viewInterval`, or the rung disabled via `activationViews <= 0`, or no
  *     evidence/parent-end anchor): `required = q0`, the rung is inert and every gate is byte-identical to pre-rung behavior.
  *   - stage 1 (intersection-safe): each step lowers `required` by one, but not below `anchorMajority = a / 2 + 1`. In this regime two
  *     conflicting shrunken certs are impossible: votes counted toward a shrunken threshold must come from anchor members, and two disjoint
  *     anchor subsets of size `> a/2` cannot exist.
  *   - stage 2 (liveness-over-partition-safety): below the anchor majority, one further reduction per [[SubMajorityStepCost]] steps, down
  *     to the hard floor [[MinQuorumFloor]] (2). This is the regime that unwedges "half the anchor is dead" (the live 3-of-6 case, where
  *     the last closed pre-restart round was signed by all 6). It knowingly trades partition safety for liveness: two disjoint healthy
  *     anchor halves under a full partition could each close divergent rounds. That window requires BOTH halves alive-but-partitioned for
  *     the entire escalation period (activation + stage-1 + 5-steps-per-peer of stage 2, tens of minutes of total silence at testnet
  *     values) and is accepted for testnet; mainnet keeps the rung disabled (no `quorum-shrink-activation-views` entry).
  *
  * Voters counted toward a shrunken threshold MUST be anchor members ([[Decision.meets]]). The anchor is a subset of
  * `roundStartFacilitators`, hence of every witness pool, so existing builder/validator pool checks remain satisfied.
  */
object QuorumDenominatorShrink {

  /** Hard floor on the shrunken required quorum. 2 is the smallest count at which a certificate still represents agreement between distinct
    * peers (a 1-vote cert would let any single node drive view changes); it intentionally matches the `MinViableCoreSize` rationale in the
    * committee ladder.
    */
  val MinQuorumFloor: Int = 2

  /** Number of escalation steps required per single quorum reduction BELOW the anchor majority (stage 2). Stage 1 reductions (down to the
    * anchor majority) cost one step each because they cannot produce conflicting certs; sub-majority reductions surrender that intersection
    * guarantee, so they are deliberately 5x slower. Compiled-in constant, jar-hash gated (the `TierTransitions.DemotionConsecutiveMisses`
    * convention).
    */
  val SubMajorityStepCost: Int = 5

  /** The per-round shrink decision. Pure data; equality across nodes holding the same shared inputs is the determinism contract (see the
    * companion scaladoc and `QuorumDenominatorShrinkSuite`'s two-node divergence test).
    *
    * @param active
    *   true only when escalation has begun AND the anchor is usable AND the shrunken requirement is actually below the base quorum. When
    *   false every helper degrades to the pre-rung arithmetic.
    * @param steps
    *   elapsed escalation steps past the activation threshold (0 when inert).
    * @param baseQuorum
    *   the unshrunken requirement, `max(coreQuorum, clusterFloor)` where `coreQuorum = max(1, fromFraction(coreSize, fraction))` and
    *   `clusterFloor = fromFraction(clusterFloorCommitteeSize, fraction)` outside bootstrap (0 in bootstrap). The floor size defaults to
    *   the frozen round committee but a layer may supply a larger, consensus-persisted high-water mark. See `decide`.
    * @param requiredQuorum
    *   the effective requirement after escalation; `baseQuorum` when inert, never below the cluster floor outside bootstrap (never below
    *   [[MinQuorumFloor]] in bootstrap).
    * @param anchor
    *   the deterministic voter-eligibility set for the shrunken margin (latest evidence `completedSigners` intersected with
    *   `roundStartFacilitators`).
    */
  final case class Decision(
    active: Boolean,
    steps: Int,
    baseQuorum: Int,
    requiredQuorum: Int,
    anchor: SortedSet[PeerId]
  ) {

    /** Cert/phase feasibility gate: full quorum always passes; the shrunken path requires `requiredQuorum` voters FROM THE ANCHOR. */
    def meets(voters: Set[PeerId]): Boolean =
      voters.size >= baseQuorum || (active && voters.count(anchor.contains) >= requiredQuorum)

    /** True when `meets` passes ONLY via the shrunken margin -- the observability trigger for rung-activation logs/counters. */
    def shrunkPath(voters: Set[PeerId]): Boolean =
      active && voters.size < baseQuorum && voters.count(anchor.contains) >= requiredQuorum

    /** Quorum size to hand to the certificate builders (which check `signers.size >= quorumSize` internally): the base quorum on the normal
      * path, the shrunken requirement on the shrunken path.
      */
    def builderQuorum(voters: Set[PeerId]): Int =
      if (voters.size >= baseQuorum) baseQuorum else requiredQuorum

    /** Threshold override for the StallDetector feasibility helpers: `Some(requiredQuorum)` only when the rung is live. */
    def quorumOverride: Option[Int] =
      if (active) Some(requiredQuorum) else None
  }

  /** Inert decision used when a caller has no anchor inputs at all; keeps every gate byte-identical to pre-rung behavior. */
  def inert(baseQuorum: Int): Decision =
    Decision(active = false, steps = 0, baseQuorum = baseQuorum, requiredQuorum = baseQuorum, anchor = SortedSet.empty[PeerId])

  /** Deterministic anchor: latest-evidence completed signers intersected with the frozen round-start committee. */
  def anchor(
    latestEvidenceSigners: Option[SortedSet[PeerId]],
    roundStartFacilitators: Set[PeerId]
  ): SortedSet[PeerId] =
    latestEvidenceSigners.fold(SortedSet.empty[PeerId])(_.filter(roundStartFacilitators.contains))

  /** Escalation steps past the activation threshold, in `viewInterval` units of wall silence since the parent outcome closed.
    * `activationViews <= 0` disables the rung entirely (the safe default); `parentEndTimeMs = None` (bootstrap / pre-v19-phase-2 parent)
    * keeps it inert.
    */
  def escalationSteps(
    nowMs: Long,
    parentEndTimeMs: Option[Long],
    viewIntervalMs: Long,
    activationViews: Int
  ): Int =
    if (activationViews <= 0) 0
    else math.max(0, ViewFromTime.compute(nowMs, parentEndTimeMs, viewIntervalMs) - activationViews)

  /** Two-stage escalation of the required quorum -- see the companion scaladoc for the regime semantics. Result is clamped to
    * `[MinQuorumFloor, baseQuorum]`; an anchor smaller than the floor disables shrinking (a 1-peer anchor cannot certify anything
    * meaningful).
    */
  def requiredQuorum(baseQuorum: Int, anchorSize: Int, steps: Int): Int =
    if (steps <= 0 || anchorSize < MinQuorumFloor) baseQuorum
    else {
      val anchorMajority = anchorSize / 2 + 1
      val safeTarget = math.max(MinQuorumFloor, math.min(baseQuorum, anchorMajority))
      val safeShrink = math.min(steps, math.max(0, baseQuorum - safeTarget))
      val extraSteps = steps - safeShrink
      val deepShrink = extraSteps / SubMajorityStepCost

      math.max(MinQuorumFloor, baseQuorum - safeShrink - deepShrink)
    }

  /** Single derivation entry point shared by every call site (phase quorums, VCC/TC assembly+apply, proposal-embedded cert validation,
    * stall feasibility) so the rung cannot drift between consumers.
    *
    * ==v4.1.0 cluster-majority floor (safety-first)==
    *
    * `applyClusterFloor` (true outside bootstrap) raises the finality quorum to at least a super/unanimity-majority of the FROZEN ROUND
    * COMMITTEE, not merely the Core sub-committee: `base = max(coreQuorum, clusterFloor)` with `clusterFloor =
    * fromFraction(clusterFloorCommitteeSize, fraction)`. The floor size defaults to the frozen `roundStartFacilitators.size`; a layer may
    * provide a larger consensus-persisted high-water mark so an outage between rounds cannot erase an already-established denominator.
    * Because `coreFacilitators` is a subset of `roundStartFacilitators`, `clusterFloor >= coreQuorum` always, so outside bootstrap `base ==
    * clusterFloor` and a Core that has shrunk to a cluster-minority can no longer assemble any certificate or finalize -- the proven 2-of-5
    * self-finalization fork is fenced. The floor also binds the SHRUNK path: `requiredQuorum` is clamped up to `clusterFloor`, so `meets`
    * (which accepts `active && anchorVoters >= requiredQuorum`) can never accept below the committee floor. Net effect outside bootstrap:
    * the shrink rung may relax the requirement DOWN TO the committee floor but never below it, so under > f genuine outage the round HALTS
    * (the caller announces it) rather than letting a minority finalize. Inside bootstrap (`applyClusterFloor = false`) `clusterFloor = 0`
    * and every value is byte-identical to pre-floor behavior, so cold start is unaffected.
    */
  def decide(
    coreSize: Int,
    applyClusterFloor: Boolean,
    quorumThresholdFraction: Double,
    latestEvidenceSigners: Option[SortedSet[PeerId]],
    roundStartFacilitators: Set[PeerId],
    parentEndTimeMs: Option[Long],
    nowMs: Long,
    viewIntervalMs: Long,
    activationViews: Int,
    clusterFloorCommitteeSize: Option[Int] = None
  ): Decision = {
    val coreQuorum = math.max(1, QuorumPolicy.fromFraction(coreSize, quorumThresholdFraction))
    val effectiveClusterFloorSize = math.max(roundStartFacilitators.size, clusterFloorCommitteeSize.getOrElse(0))
    val clusterFloor =
      if (applyClusterFloor) math.max(1, QuorumPolicy.fromFraction(effectiveClusterFloorSize, quorumThresholdFraction)) else 0
    val base = math.max(coreQuorum, clusterFloor)
    val anchorSet = anchor(latestEvidenceSigners, roundStartFacilitators)
    val steps = escalationSteps(nowMs, parentEndTimeMs, viewIntervalMs, activationViews)
    val required = math.max(clusterFloor, requiredQuorum(base, anchorSet.size, steps))

    Decision(
      active = steps > 0 && anchorSet.size >= MinQuorumFloor && required < base,
      steps = steps,
      baseQuorum = base,
      requiredQuorum = required,
      anchor = anchorSet
    )
  }
}
