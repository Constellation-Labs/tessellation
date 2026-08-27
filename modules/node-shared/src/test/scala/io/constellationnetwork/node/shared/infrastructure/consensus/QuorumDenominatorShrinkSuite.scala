package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.data.NonEmptySet

import scala.collection.immutable.SortedSet

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import weaver.FunSuite

/** Pure-function coverage for the v33 escalating quorum-denominator shrink (`QuorumDenominatorShrink`).
  *
  * The suite pins four contracts:
  *
  *   1. '''Two-node divergent-local-observation determinism''': two honest nodes share `lastOutcome` (hence the evidence anchor and the
  *      parent end time) and the frozen round-start committee, but hold DIFFERENT local extras -- different locally-received vote subsets
  *      and wall clocks that differ by NTP-scale skew inside the same escalation step. They must derive the identical `Decision` (same
  *      `requiredQuorum`, same anchor), and feasibility may differ only through the voter sets each one feeds in, never through the
  *      thresholds.
  *   1. '''Escalation threshold''': the rung is byte-inert (required == base, `meets` == legacy size gate) until the silence since the
  *      parent outcome exceeds `activationViews * viewInterval`, and permanently inert when disabled (`activationViews <= 0`) or when the
  *      anchors are absent (no evidence entry / no parent end time / anchor below the floor).
  *   1. '''Floor''': the required quorum never drops below `MinQuorumFloor` (2) no matter how many steps elapse, stage-1 shrink stops at
  *      the anchor majority, and stage-2 (sub-majority) reductions cost `SubMajorityStepCost` steps each.
  *   1. '''Anchor restriction''': votes counted toward a shrunken threshold must come from the anchor set -- non-anchor voters can never
  *      assemble a shrunken-margin certificate.
  *
  * The final tests drive `ProposalVccValidator.validate` with an active decision to lock in the follower-side acceptance of shrunken-quorum
  * VCC/TC certs (the cross-node site where a determinism failure would reproduce the alpha.92 stale-proposal-rejection wedge).
  */
object QuorumDenominatorShrinkSuite extends FunSuite {

  private val testnetFraction: Double = 0.6666666666666666
  private val viewIntervalMs: Long = 60000L
  private val parentEndMs: Long = 1000000L
  private val activationViews: Int = 10

  private def pid(tag: String): PeerId = PeerId(Hex(tag * 32))

  // The live ord-3150197 shape: committee of 6, three healthy source nodes, three
  // gossip-responsive-but-consensus-dead peers (incl. the silent leader).
  private val healthyA = pid("aa")
  private val healthyB = pid("bb")
  private val healthyC = pid("cc")
  private val deadD = pid("dd")
  private val deadE = pid("ee")
  private val deadLeader = pid("ff")
  private val committee: Set[PeerId] = Set(healthyA, healthyB, healthyC, deadD, deadE, deadLeader)
  private val healthyTrio: Set[PeerId] = Set(healthyA, healthyB, healthyC)

  // Pre-restart the cluster was healthy, so the last closed round's canonical
  // completedSigners contains ALL SIX peers -- the worst case for the anchor.
  private val fullAnchorSigners: Option[SortedSet[PeerId]] = Some(SortedSet.empty[PeerId] ++ committee)

  private def nowAtSteps(steps: Int): Long =
    parentEndMs + (activationViews.toLong + steps.toLong) * viewIntervalMs

  // Defaults to applyClusterFloor = false: these cases exercise the SHRINK RUNG in isolation (requiredQuorum
  // walking down toward MinQuorumFloor), which outside bootstrap is neutralized by the v4.1.0 cluster floor.
  // The floor-ON regime is covered by the dedicated cluster-floor tests below.
  private def decideAt(
    steps: Int,
    latestSigners: Option[SortedSet[PeerId]] = fullAnchorSigners,
    parentEnd: Option[Long] = Some(parentEndMs),
    activation: Int = activationViews,
    coreSize: Int = 6,
    nowOffsetMs: Long = 0L,
    applyClusterFloor: Boolean = false
  ): QuorumDenominatorShrink.Decision =
    QuorumDenominatorShrink.decide(
      coreSize = coreSize,
      applyClusterFloor = applyClusterFloor,
      quorumThresholdFraction = testnetFraction,
      latestEvidenceSigners = latestSigners,
      roundStartFacilitators = committee,
      parentEndTimeMs = parentEnd,
      nowMs = nowAtSteps(steps) + nowOffsetMs,
      viewIntervalMs = viewIntervalMs,
      activationViews = activation
    )

  // ----------------------------------------------------------------------------
  // Determinism: two-node divergent-local-observation test.
  // ----------------------------------------------------------------------------

  test("two nodes with identical shared inputs but divergent local extras derive the identical Decision") {
    // Node A: clock 9s into the step window, has locally received 5 votes.
    // Node B: clock 51s into the same step window, has locally received only 3 votes.
    // The Decision is a function of shared inputs only, so it must be identical.
    val nodeA = decideAt(steps = 5, nowOffsetMs = 9000L)
    val nodeB = decideAt(steps = 5, nowOffsetMs = 51000L)
    val nodeALocalVotes = committee - deadLeader // 5 locally-seen voters
    val nodeBLocalVotes = healthyTrio // 3 locally-seen voters

    expect.same(nodeA, nodeB) &&
    expect.same(nodeA.requiredQuorum, nodeB.requiredQuorum) &&
    expect.same(nodeA.anchor, nodeB.anchor) &&
    // Local vote subsets affect only the argument to `meets`, never the thresholds:
    // both nodes agree the trio satisfies the shrunken requirement.
    expect(nodeA.meets(nodeALocalVotes)) &&
    expect(nodeB.meets(nodeBLocalVotes))
  }

  test("wall-clock skew across a step boundary changes only WHEN a node activates, monotonically") {
    val justBefore = decideAt(steps = 0, nowOffsetMs = -1L)
    val justAfter = decideAt(steps = 0, nowOffsetMs = 0L)

    expect(justBefore.steps == 0 || justAfter.steps >= justBefore.steps) &&
    expect(justAfter.requiredQuorum <= justBefore.requiredQuorum)
  }

  // ----------------------------------------------------------------------------
  // Escalation threshold: inert below activation; disabled config; absent anchors.
  // ----------------------------------------------------------------------------

  test("rung is inert before the activation threshold (steps == 0)") {
    val decision = QuorumDenominatorShrink.decide(
      coreSize = 6,
      applyClusterFloor = false,
      quorumThresholdFraction = testnetFraction,
      latestEvidenceSigners = fullAnchorSigners,
      roundStartFacilitators = committee,
      parentEndTimeMs = Some(parentEndMs),
      nowMs = parentEndMs + (activationViews.toLong * viewIntervalMs) - 1L,
      viewIntervalMs = viewIntervalMs,
      activationViews = activationViews
    )

    expect(!decision.active) &&
    expect.same(decision.baseQuorum, decision.requiredQuorum) &&
    expect(!decision.meets(healthyTrio)) &&
    expect(decision.meets(committee - deadLeader - deadE)) // 4 voters == base quorum still passes
  }

  test("rung is disabled when activationViews <= 0, regardless of elapsed silence") {
    val decision = decideAt(steps = 1000, activation = 0)

    expect(!decision.active) &&
    expect.same(decision.baseQuorum, decision.requiredQuorum) &&
    expect(!decision.meets(healthyTrio))
  }

  test("rung is inert without a parent end-time anchor (bootstrap / pre-phase-2 parent)") {
    val decision = decideAt(steps = 1000, parentEnd = None)

    expect(!decision.active) && expect(!decision.meets(healthyTrio))
  }

  test("rung is inert without an evidence anchor (empty controllerEvidence window)") {
    val decision = decideAt(steps = 1000, latestSigners = None)

    expect(!decision.active) && expect(!decision.meets(healthyTrio))
  }

  test("rung is inert when the anchor is below the floor (1-peer anchor cannot certify)") {
    val decision = decideAt(steps = 1000, latestSigners = Some(SortedSet(healthyA)))

    expect(!decision.active) && expect(!decision.meets(healthyTrio))
  }

  // ----------------------------------------------------------------------------
  // Escalation cadence + floor.
  // ----------------------------------------------------------------------------

  test("full-committee anchor (majority == base): stage 2 cadence, one reduction per SubMajorityStepCost steps") {
    // core=6 -> base=4; anchor=6 -> anchorMajority=4, so stage 1 contributes nothing
    // and every reduction is a deliberate sub-majority (stage 2) step.
    val expected = List(
      (0, 4, false),
      (1, 4, false),
      (4, 4, false),
      (5, 3, true), // first stage-2 reduction: the live wedge unwedges here (trio == 3)
      (9, 3, true),
      (10, 2, true), // hard floor
      (1000, 2, true) // never below MinQuorumFloor
    )
    val mismatches = expected.flatMap {
      case (steps, required, active) =>
        val d = decideAt(steps)
        if (d.requiredQuorum == required && d.active == active) None
        else Some((steps, d.requiredQuorum, d.active))
    }

    expect.same(List.empty, mismatches)
  }

  test("partial anchor (majority < base): stage 1 shrinks one per step down to the anchor majority") {
    // Anchor of 4 (e.g. the last closed round had 4 canonical responders) -> majority 3.
    val anchor4 = Some(SortedSet(healthyA, healthyB, healthyC, deadD))
    val atStep1 = decideAt(steps = 1, latestSigners = anchor4)
    val atStep5 = decideAt(steps = 5, latestSigners = anchor4)
    val atStep6 = decideAt(steps = 6, latestSigners = anchor4)

    expect.same(3, atStep1.requiredQuorum) && // stage 1: 4 -> 3 after one step
    expect.same(3, atStep5.requiredQuorum) && // stage 2 not yet paid for
    expect.same(2, atStep6.requiredQuorum) // 1 stage-1 step + 5 stage-2 steps -> floor
  }

  test("requiredQuorum never exceeds baseQuorum and never drops below MinQuorumFloor") {
    val violations = (0 to 200).flatMap { steps =>
      val d = decideAt(steps)
      if (d.requiredQuorum <= d.baseQuorum && d.requiredQuorum >= QuorumDenominatorShrink.MinQuorumFloor) None
      else Some((steps, d.requiredQuorum))
    }

    expect.same(List.empty, violations.toList)
  }

  // ----------------------------------------------------------------------------
  // Anchor restriction on the shrunken margin.
  // ----------------------------------------------------------------------------

  test("shrunken margin counts only anchor voters") {
    val outsiderX = pid("11")
    val outsiderY = pid("22")
    val outsiderZ = pid("33")
    // Anchor restricted to the healthy trio (e.g. they were the only canonical responders
    // of the last closed round). Outsiders cannot form a shrunken-margin quorum.
    val decision = decideAt(steps = 50, latestSigners = Some(SortedSet(healthyA, healthyB, healthyC)))

    expect(decision.active) &&
    expect(!decision.meets(Set(outsiderX, outsiderY, outsiderZ))) &&
    expect(decision.meets(Set(healthyA, healthyB))) // floor 2, both in anchor
  }

  test("builderQuorum hands the base quorum to the builder on the normal path, the shrunken requirement otherwise") {
    val decision = decideAt(steps = 5)

    expect.same(decision.baseQuorum, decision.builderQuorum(committee - deadLeader - deadE)) && // 4 voters
    expect.same(decision.requiredQuorum, decision.builderQuorum(healthyTrio))
  }

  test("shrunkPath is true only when the gate passes exclusively via the shrunken margin") {
    val decision = decideAt(steps = 5)

    expect(decision.shrunkPath(healthyTrio)) &&
    expect(!decision.shrunkPath(committee - deadLeader - deadE)) && // full quorum, no shrink needed
    expect(!decision.shrunkPath(Set(healthyA, healthyB))) // below the shrunken requirement
  }

  test("finalization predicate: shrunk anchor-majority finalizes below the (n/2)+1 majority gate") {
    // The dag-l0 finalization gate is `validSignatures.size >= (coreSize/2)+1 || shrinkDecision.shrunkPath(signerIds)`
    // (currency-l0 reaches the same Decision via maybeGetAllDeclarations.meets). This pins that the shrunk path
    // lets the healthy trio finalize while staying inert when the rung is off -- the gap the original wiring missed.
    val majority = (6 / 2) + 1 // strict-majority finalization base at core=6 == 4
    val active = decideAt(steps = 5) // required == 3
    val inert = decideAt(steps = 0)
    def canFinalize(d: QuorumDenominatorShrink.Decision)(signers: Set[PeerId]): Boolean =
      signers.size >= majority || d.shrunkPath(signers)

    expect(canFinalize(active)(healthyTrio)) && // 3 anchor sigs finalize via the shrunk path
    expect(!canFinalize(active)(Set(healthyA, healthyB))) && // 2 < required(3), no finalize
    expect(canFinalize(active)(committee - deadLeader - deadE)) && // 4 == majority, normal path
    expect(!canFinalize(inert)(healthyTrio)) && // rung off: only the majority gate applies, 3 < 4
    expect(canFinalize(inert)(committee - deadLeader - deadE)) // rung off: 4 == majority still finalizes
  }

  // ----------------------------------------------------------------------------
  // v4.1.0 cluster-majority floor (applyClusterFloor = true). A Core that has shrunk to a
  // cluster-minority must NEVER finalize -- on the normal path OR the shrunk/anchor path -- because
  // the quorum is floored at a super/unanimity-majority of the FROZEN ROUND COMMITTEE. This is the
  // proven 2-of-5 fork; the floor is the safety fix. Mirrors `QuorumDenominatorShrink.decide`.
  // ----------------------------------------------------------------------------

  // The proven fork shape: a committee of 5, whose Core shrank to 2 (CommitteeBuilder "Shrink" rung).
  private val n1 = pid("a1")
  private val n2 = pid("a2")
  private val n3 = pid("a3")
  private val n4 = pid("a4")
  private val n5 = pid("a5")
  private val committee5: Set[PeerId] = Set(n1, n2, n3, n4, n5)
  private val minority2: Set[PeerId] = Set(n1, n2)
  private val majority4: Set[PeerId] = Set(n1, n2, n3, n4)

  private def decideFloor(
    steps: Int,
    coreSize: Int,
    fraction: Double = testnetFraction,
    latestSigners: Option[SortedSet[PeerId]] = Some(SortedSet.empty[PeerId] ++ committee5),
    committeeSet: Set[PeerId] = committee5,
    applyClusterFloor: Boolean = true
  ): QuorumDenominatorShrink.Decision =
    QuorumDenominatorShrink.decide(
      coreSize = coreSize,
      applyClusterFloor = applyClusterFloor,
      quorumThresholdFraction = fraction,
      latestEvidenceSigners = latestSigners,
      roundStartFacilitators = committeeSet,
      parentEndTimeMs = Some(parentEndMs),
      nowMs = nowAtSteps(steps),
      viewIntervalMs = viewIntervalMs,
      activationViews = activationViews
    )

  test("cluster floor: a 2-of-5 minority Core cannot finalize on the normal path (supermajority committee floor)") {
    val d = decideFloor(steps = 0, coreSize = 2)
    expect.same(4, d.baseQuorum) && // fromFraction(5, 2/3) == 4, NOT fromFraction(2, 2/3) == 2
    expect(!d.meets(minority2), s"2-of-5 minority must not meet the committee floor of 4, got meets=true") &&
    expect(d.meets(majority4), "a committee super-majority (4 of 5) must finalize")
  }

  test("cluster floor retains a certified five-seat denominator when the next round contains four peers") {
    val currentFour = Set(n1, n2, n3, n4)
    val d = QuorumDenominatorShrink.decide(
      coreSize = 3,
      applyClusterFloor = true,
      quorumThresholdFraction = testnetFraction,
      latestEvidenceSigners = Some(SortedSet.empty[PeerId] ++ currentFour),
      roundStartFacilitators = currentFour,
      parentEndTimeMs = Some(parentEndMs),
      nowMs = nowAtSteps(0),
      viewIntervalMs = viewIntervalMs,
      activationViews = activationViews,
      clusterFloorCommitteeSize = Some(5)
    )

    expect.same(4, d.baseQuorum) &&
    expect(!d.meets(Set(n1, n2, n3)), "three signatures must not satisfy the retained five-seat floor") &&
    expect(d.meets(currentFour), "all four reachable validators satisfy the retained five-seat floor")
  }

  test("cluster floor: the shrink rung cannot relax below the committee floor (2-of-5 still rejected after deep silence)") {
    // Full anchor + maximum escalation: pre-floor this walked requiredQuorum down to MinQuorumFloor=2.
    // With the floor active, requiredQuorum is clamped UP to the committee floor, so the rung is neutralized
    // and a minority anchor can never assemble a shrunken-margin cert.
    val d = decideFloor(steps = 1000, coreSize = 2)
    expect(d.requiredQuorum >= 4, s"requiredQuorum must not drop below the committee floor 4, got ${d.requiredQuorum}") &&
    expect.same(d.baseQuorum, d.requiredQuorum) && // floored == base
    expect(!d.active, "the rung must be inert (neutralized) once the floor binds") &&
    expect(!d.meets(minority2), "2 anchor voters must not finalize via the shrunk path under the floor") &&
    expect(!d.meets(Set(n1, n3)), "any 2-subset must be rejected under the floor")
  }

  test("cluster floor under unanimity (currency-l0 fraction=1.0): the entire round committee is required") {
    val d = decideFloor(steps = 0, coreSize = 2, fraction = 1.0)
    expect.same(5, d.baseQuorum) && // unanimity(5) == 5
    expect(!d.meets(majority4), "under unanimity even 4 of 5 must not finalize") &&
    expect(d.meets(committee5), "the full committee must finalize")
  }

  test("cluster floor: a healthy committee (Core == committee) is unchanged by the floor") {
    // When Core == roundStartFacilitators the floor equals the Core quorum, so floor-on and floor-off agree.
    val floored = decideFloor(steps = 0, coreSize = 5)
    val unfloored = decideFloor(steps = 0, coreSize = 5, applyClusterFloor = false)
    expect.same(unfloored.baseQuorum, floored.baseQuorum) &&
    expect.same(4, floored.baseQuorum) &&
    expect(floored.meets(majority4) == unfloored.meets(majority4), "healthy committee gate must be identical") &&
    expect(!floored.meets(minority2), "even healthy, a 2-of-5 cannot finalize")
  }

  test("bootstrap exemption: applyClusterFloor=false is byte-identical to pre-floor (Core-only, no floor)") {
    // The floor MUST be off during cold start, or genesis (which finalizes solo / small-Core) deadlocks.
    val d = decideFloor(steps = 0, coreSize = 2, applyClusterFloor = false)
    expect.same(2, d.baseQuorum) && // Core-only fromFraction(2, 2/3) == 2; floor NOT applied
    expect(d.meets(minority2), "in bootstrap a 2-Core CAN finalize (cold-start liveness); the floor turns on post-bootstrap")
  }

  test("cluster floor determinism: two nodes with the same shared inputs derive the identical floored Decision") {
    val a = decideFloor(steps = 7, coreSize = 2)
    val b = decideFloor(steps = 7, coreSize = 2)
    expect.same(a, b) && expect.same(a.baseQuorum, b.baseQuorum) && expect.same(a.requiredQuorum, b.requiredQuorum)
  }

  // ----------------------------------------------------------------------------
  // v4.1.0 collection/gate consistency (Codex review fix). The phase-gate declaration-collection universe
  // must include the FROZEN round committee when the floor is active, so a member dropped from the mutable
  // active set mid-round (a B1 eviction shrinks state.facilitators at proposal acceptance; a withdrawal also
  // removes it) still contributes its declaration to the finality count -- otherwise a round the frozen
  // committee could close DEADLOCKS below the floor.
  // ----------------------------------------------------------------------------

  test("collection universe includes the frozen committee when the floor is active (no mid-round-eviction deadlock)") {
    // Active set shrank to 3 (n4, n5 evicted mid-round) but the frozen committee is still 5.
    val active = Set(n1, n2, n3)
    val universe = ConsensusStateAdvancer.collectionUniverse(active, committee5, floorActive = true)
    expect(committee5.subsetOf(universe), s"frozen committee must remain in the lookup universe, got $universe") &&
    expect(
      universe.contains(n4) && universe.contains(n5),
      "frozen members evicted from the active set must still be looked up so their declarations count"
    )
  }

  test("collection universe is exactly the active set when the floor is off (bootstrap byte-identical)") {
    val active = Set(n1, n2, n3)
    val coreSet = Set(n1, n2) // gate set is Core in bootstrap; must NOT widen the collection universe
    expect.same(active, ConsensusStateAdvancer.collectionUniverse(active, coreSet, floorActive = false))
  }

  // ----------------------------------------------------------------------------
  // Follower-side cert validation (ProposalVccValidator) under an active decision.
  // ----------------------------------------------------------------------------

  private val proposalHash: Hash = Hash.fromBytes("proposal_hash".getBytes("UTF-8"))
  private val facHash: Hash = Hash.fromBytes("facilitators_hash".getBytes("UTF-8"))
  private val lastSnap: Hash = Hash.fromBytes("last_snapshot_hash".getBytes("UTF-8"))

  private def signerProof(tag: String): SignatureProof =
    SignatureProof(Id(Hex(tag * 32)), Signature(Hex("00")))

  private def vccOf(voterTags: List[String]): ViewChangeCertificate = {
    val votes = voterTags.map { tag =>
      Signed(
        ViewChangeVote(0L, 1L, facHash, lastSnap, None),
        NonEmptySet.of(signerProof(tag))
      )
    }

    ViewChangeCertificate(0L, 1L, facHash, NonEmptySet.of(votes.head, votes.tail: _*))
  }

  private def validateWith(
    vcc: ViewChangeCertificate,
    quorumShrink: Option[QuorumDenominatorShrink.Decision]
  ): Either[ProposalRejection, Unit] =
    ProposalVccValidator.validate(
      proposalView = 1L,
      proposalHash = proposalHash,
      proposalVcc = Some(vcc),
      initialViewNumber = 0,
      coreSize = 6,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      eligibleFacilitators = committee,
      roundStartFacilitators = committee,
      peerQuality = Map.empty,
      quorumThresholdFraction = testnetFraction,
      minParticipationObservations = 5,
      quorumShrink = quorumShrink
    )

  test("validator rejects a 3-vote VCC against core=6 without an active shrink decision (pre-v33 behavior preserved)") {
    val vcc3 = vccOf(List("aa", "bb", "cc"))
    val withoutShrink = validateWith(vcc3, None)
    val inertShrink = validateWith(vcc3, Some(decideAt(steps = 0)))

    expect(withoutShrink.isLeft, s"expected vcc_under_quorum, got $withoutShrink") &&
    expect(inertShrink.isLeft, s"expected vcc_under_quorum under inert decision, got $inertShrink")
  }

  test("validator accepts a 3-anchor-vote VCC once the shrink decision is active at required=3") {
    val vcc3 = vccOf(List("aa", "bb", "cc"))
    val result = validateWith(vcc3, Some(decideAt(steps = 5)))

    expect(result.isRight, s"expected shrunken-quorum acceptance, got $result")
  }

  test("validator rejects a shrunken-margin VCC whose voters are outside the anchor") {
    // Anchor narrowed to the trio; voters dd/ee/ff are committee members but not anchor members.
    val decision = decideAt(steps = 5, latestSigners = Some(SortedSet(healthyA, healthyB, healthyC)))
    val outsiderVcc = vccOf(List("dd", "ee", "ff"))
    val result = validateWith(outsiderVcc, Some(decision))

    expect(result.isLeft, s"expected rejection of non-anchor shrunken margin, got $result")
  }

  test("validator unions roundStartFacilitators into the witness pool, matching the assembler (vcc_voter_not_in_pool fix)") {
    // healthyC is a round-start facilitator and anchor member but is ABSENT from eligibleFacilitators
    // and from peerQuality, so WitnessPool.all alone would exclude it and reject the cert as
    // vcc_voter_not_in_pool -- exactly the assembler/validator asymmetry the v33 shrink path exposes
    // (the assembler's widerWitnessPoolAll unions roundStartFacilitators; the validator must too).
    val eligibleWithoutC = committee - healthyC
    val vcc3 = vccOf(List("aa", "bb", "cc"))
    val withUnion = ProposalVccValidator.validate(
      proposalView = 1L,
      proposalHash = proposalHash,
      proposalVcc = Some(vcc3),
      initialViewNumber = 0,
      coreSize = 6,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      eligibleFacilitators = eligibleWithoutC,
      roundStartFacilitators = committee,
      peerQuality = Map.empty,
      quorumThresholdFraction = testnetFraction,
      minParticipationObservations = 5,
      quorumShrink = Some(decideAt(steps = 5))
    )
    // Same inputs but WITHOUT C in roundStartFacilitators either: the union cannot rescue it, so the
    // round-start-only anchor voter is genuinely out of pool and the cert is rejected.
    val withoutUnion = ProposalVccValidator.validate(
      proposalView = 1L,
      proposalHash = proposalHash,
      proposalVcc = Some(vcc3),
      initialViewNumber = 0,
      coreSize = 6,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      eligibleFacilitators = eligibleWithoutC,
      roundStartFacilitators = eligibleWithoutC,
      peerQuality = Map.empty,
      quorumThresholdFraction = testnetFraction,
      minParticipationObservations = 5,
      quorumShrink = Some(decideAt(steps = 5))
    )

    expect(withUnion.isRight, s"round-start anchor voter absent from eligibleFacilitators must validate via the union, got $withUnion") &&
    expect(withoutUnion.isLeft, s"voter absent from BOTH pools must reject (vcc_voter_not_in_pool), got $withoutUnion")
  }
}
