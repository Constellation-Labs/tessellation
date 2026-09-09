package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.infrastructure.consensus.ActiveFacilitatorAdmission.ExclusionReason
import io.constellationnetwork.node.shared.infrastructure.selfhealth.SelfHealthHint
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

object ActiveFacilitatorAdmissionSuite extends SimpleIOSuite {

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))
  private def ord(n: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(n)

  private val a = peer('a')
  private val b = peer('b')
  private val c = peer('c')
  private val d = peer('d')
  private val e = peer('e')

  private def window(entries: (Long, Set[PeerId])*): SortedMap[SnapshotOrdinal, SortedSet[PeerId]] =
    SortedMap.from(entries.toList.map { case (o, ps) => ord(o) -> SortedSet.from(ps) })

  private def fromRecent(
    selected: List[PeerId],
    recentSigners: SortedMap[SnapshotOrdinal, SortedSet[PeerId]],
    peerQuality: Map[PeerId, (Int, Int)] = Map.empty,
    activeScores: Map[PeerId, Int] = Map.empty,
    minActiveSize: Int = 2,
    targetActiveSize: Int = 3,
    maxActiveSize: Int = 4,
    minParticipationObservations: Int = 3,
    minParticipationRatio: Double = 0.5,
    maxExpansionPerRound: Int = Int.MaxValue,
    minProbationReentrySlots: Int = 0,
    recentSignerWindow: Int = TierTransitions.DemotionConsecutiveMisses
  ): ActiveFacilitatorAdmission.Result =
    ActiveFacilitatorAdmission.fromRecentSigners(
      selected = selected,
      recentSigners = recentSigners,
      peerQuality = peerQuality,
      activeScores = activeScores,
      minActiveSize = minActiveSize,
      targetActiveSize = targetActiveSize,
      maxActiveSize = maxActiveSize,
      minParticipationObservations = minParticipationObservations,
      minParticipationRatio = minParticipationRatio,
      maxExpansionPerRound = maxExpansionPerRound,
      minProbationReentrySlots = minProbationReentrySlots,
      recentSignerWindow = recentSignerWindow
    )

  pureTest("canonical facilitator base uses only parent facilitators") {
    val result = ConsensusPeerController.canonicalFacilitatorBase(
      parentFacilitators = List(a, b, a),
      seedlistPeerIds = List(a, b, c, d)
    )

    expect.same(List(a, b), result)
  }

  pureTest("certified admissions append to parent facilitators deterministically") {
    val result = ConsensusPeerController.applyCertifiedAdmissions(
      parentFacilitators = List(b, a, b),
      admittedPeers = Set(d, c, a)
    )

    expect.same(List(b, a, c, d), result)
  }

  pureTest("legacy outcome membership carries removal evidence without deleting a signing lease") {
    val result = ConsensusPeerController.applyNextRoundCertifiedMembership(
      roundStartFacilitators = List(a, b, c),
      admittedPeers = List(d),
      certifiedEvictedPeers = None
    )

    // A legacy removedFacilitators set containing `b` is intentionally not an input. Below
    // activation it remains evidence beside the outcome, not authority to contract this roster.
    expect.same(List(a, b, c, d), result)
  }

  pureTest("v35 outcome membership applies certified evictions only at the next-round boundary") {
    val result = ConsensusPeerController.applyNextRoundCertifiedMembership(
      roundStartFacilitators = List(a, b, c),
      admittedPeers = List(d),
      certifiedEvictedPeers = Some(List(b))
    )

    expect.same(List(a, c, d), result)
  }

  pureTest("does not filter when recent signer window is not deep enough") {
    val result = fromRecent(
      selected = List(a, b, c),
      recentSigners = window(1L -> Set(a, b)),
      minActiveSize = 2
    )

    expect.same(List(a, b, c), result.active) &&
    expect(result.exclusions.isEmpty) &&
    expect(!result.recentFilterApplied)
  }

  pureTest("expands beyond recent signers with quality-ranked candidates") {
    val result = fromRecent(
      selected = List(a, b, c, d),
      recentSigners = window(
        10L -> Set(a, b),
        11L -> Set(a, b),
        12L -> Set(a, b)
      ),
      peerQuality = Map(c -> (5, 5), d -> (1, 5)),
      targetActiveSize = 3
    )

    expect.same(List(a, b, c), result.active) &&
    expect.same(Set(d), result.exclusions.collect { case e if e.reason == ExclusionReason.QualityBelowThreshold => e.peerId }.toSet) &&
    expect.same(1, result.expansionAdmittedSize) &&
    expect(result.recentFilterApplied)
  }

  pureTest("bypasses filter when recent signer pool would fall below active floor") {
    val result = fromRecent(
      selected = List(a, b, c),
      recentSigners = window(
        10L -> Set(a),
        11L -> Set(a),
        12L -> Set(a)
      ),
      minActiveSize = 2
    )

    expect.same(List(a, b, c), result.active) &&
    expect(result.exclusions.isEmpty) &&
    expect(!result.recentFilterApplied)
  }

  pureTest("ranks recent signers by window count before selected order") {
    val result = fromRecent(
      selected = List(c, a, d, b),
      recentSigners = window(
        10L -> Set(a, b, c),
        11L -> Set(a, b),
        12L -> Set(a, b)
      ),
      minActiveSize = 2
    )

    expect.same(List(a, b, c), result.active) &&
    expect.same(1, result.recentSignerMinCount) &&
    expect.same(3, result.recentSignerMaxCount)
  }

  pureTest("uses quality and stable peer id as tie-breakers among equally recent signers") {
    val result = fromRecent(
      selected = List(c, a, b),
      recentSigners = window(
        10L -> Set(a, b, c),
        11L -> Set(a, b, c),
        12L -> Set(a, b, c)
      ),
      peerQuality = Map(a -> (3, 3), b -> (2, 3), c -> (3, 6)),
      minActiveSize = 2
    )

    expect.same(List(a, b, c), result.active)
  }

  pureTest("target is not a cap for recent signers") {
    val result = fromRecent(
      selected = List(a, b, c, d),
      recentSigners = window(
        10L -> Set(a, b, c, d),
        11L -> Set(a, b, c, d),
        12L -> Set(a, b, c, d)
      ),
      targetActiveSize = 3,
      maxActiveSize = 4
    )

    expect.same(List(a, b, c, d), result.active) &&
    expect.same(4, result.targetSize)
  }

  pureTest("promoted expansion candidates rank before bounded probation candidates") {
    val result = fromRecent(
      selected = List(a, b, c, d),
      recentSigners = window(
        10L -> Set(a, b),
        11L -> Set(a, b),
        12L -> Set(a, b)
      ),
      peerQuality = Map(c -> (5, 5), d -> (5, 5)),
      activeScores = Map(a -> 80, b -> 80, c -> 99, d -> 100),
      targetActiveSize = 4
    )

    expect.same(List(a, b, d, c), result.active) &&
    expect(result.exclusions.isEmpty) &&
    expect.same(2, result.expansionAdmittedSize) &&
    expect.same(1, result.probationAdmittedSize)
  }

  pureTest("probation candidates remain bounded by expansion rate") {
    val result = fromRecent(
      selected = List(a, b, c, d),
      recentSigners = window(
        10L -> Set(a, b),
        11L -> Set(a, b),
        12L -> Set(a, b)
      ),
      peerQuality = Map(c -> (1, 1), d -> (1, 1)),
      activeScores = Map(a -> 80, b -> 80),
      targetActiveSize = 4,
      maxExpansionPerRound = 1
    )

    expect.same(List(a, b, c), result.active) &&
    expect.same(List(c), result.probationAdmitted) &&
    expect.same(Set(d), result.exclusions.collect { case e if e.reason == ExclusionReason.ScoreBelowPromoteThreshold => e.peerId }.toSet) &&
    expect.same(1, result.expansionAdmittedSize) &&
    expect.same(1, result.probationAdmittedSize)
  }

  // Bounded probation re-entry lane (catch-22 fix): a 3-peer recent-signer core fills below the
  // active target, several below-promote-threshold "rehabilitating" peers wait in `selected`, and
  // maxExpansionPerRound = 1 throttles the legacy re-entry path to a single probation slot. With
  // minProbationReentrySlots = 2 at least two of the scoreExcluded peers re-enter the active set
  // in one round, draining a post-outage backlog in a bounded number of rounds.
  pureTest("probation re-entry lane admits multiple rehabilitating peers despite expansion throttle") {
    val result = fromRecent(
      selected = List(a, b, c, d, e),
      recentSigners = window(
        10L -> Set(a, b, c),
        11L -> Set(a, b, c),
        12L -> Set(a, b, c)
      ),
      peerQuality = Map(d -> (5, 5), e -> (5, 5)),
      activeScores = Map(a -> 120, b -> 120, c -> 120, d -> 60, e -> 60),
      minActiveSize = 3,
      targetActiveSize = 5,
      maxActiveSize = 5,
      maxExpansionPerRound = 1,
      minProbationReentrySlots = 2
    )

    expect.same(List(d, e), result.probationAdmitted) &&
    expect.same(2, result.probationAdmittedSize) &&
    expect(result.active.contains(d)) &&
    expect(result.active.contains(e))
  }

  // Companion: minProbationReentrySlots = 0 reproduces the pre-fix behavior exactly -- the lane is
  // inert and probation re-entry is throttled by maxExpansionPerRound to a single slot.
  pureTest("probation re-entry lane is inert at zero (pre-fix expansion-limited behavior)") {
    val result = fromRecent(
      selected = List(a, b, c, d, e),
      recentSigners = window(
        10L -> Set(a, b, c),
        11L -> Set(a, b, c),
        12L -> Set(a, b, c)
      ),
      peerQuality = Map(d -> (5, 5), e -> (5, 5)),
      activeScores = Map(a -> 120, b -> 120, c -> 120, d -> 60, e -> 60),
      minActiveSize = 3,
      targetActiveSize = 5,
      maxActiveSize = 5,
      maxExpansionPerRound = 1,
      minProbationReentrySlots = 0
    )

    expect.same(List(d), result.probationAdmitted) &&
    expect.same(1, result.probationAdmittedSize)
  }

  // Cap: the lane never grows the active set beyond maxActiveSize (configuredMax), even when
  // minProbationReentrySlots greatly exceeds the available headroom.
  pureTest("probation re-entry lane never exceeds the active-set max") {
    val result = fromRecent(
      selected = List(a, b, c, d, e),
      recentSigners = window(
        10L -> Set(a, b, c),
        11L -> Set(a, b, c),
        12L -> Set(a, b, c)
      ),
      peerQuality = Map(d -> (5, 5), e -> (5, 5)),
      activeScores = Map(a -> 120, b -> 120, c -> 120, d -> 60, e -> 60),
      minActiveSize = 3,
      targetActiveSize = 5,
      maxActiveSize = 5,
      maxExpansionPerRound = 1,
      minProbationReentrySlots = 10
    )

    expect(result.active.size <= 5) &&
    expect(result.active.toSet.size == result.active.size)
  }

  // Determinism: permuting the `selected` input ordering yields identical probationAdmitted because
  // selection is sorted by probationRank (which ends in a stable PeerId tiebreak). Required so all
  // honest nodes derive the same committee and facilitatorsHash.
  pureTest("probation re-entry lane selection is deterministic under input permutation") {
    def run(selected: List[PeerId]): ActiveFacilitatorAdmission.Result =
      fromRecent(
        selected = selected,
        recentSigners = window(
          10L -> Set(a, b, c),
          11L -> Set(a, b, c),
          12L -> Set(a, b, c)
        ),
        peerQuality = Map(d -> (5, 5), e -> (5, 5)),
        activeScores = Map(a -> 120, b -> 120, c -> 120, d -> 60, e -> 60),
        minActiveSize = 3,
        targetActiveSize = 5,
        maxActiveSize = 5,
        maxExpansionPerRound = 1,
        minProbationReentrySlots = 2
      )

    val ordered = run(List(a, b, c, d, e))
    val permuted = run(List(e, d, c, b, a))

    expect.same(ordered.probationAdmitted, permuted.probationAdmitted)
  }

  // Non-quorum: probation peers are returned in the probationAdmitted field (so the caller routes
  // them to nonCorePeers in CommitteeBuilder) and are NOT double-counted as recent-signer-pool
  // members. recentSignerPoolSize reflects only the always-on core.
  pureTest("probation re-entry lane peers are not counted into the recent-signer pool") {
    val result = fromRecent(
      selected = List(a, b, c, d, e),
      recentSigners = window(
        10L -> Set(a, b, c),
        11L -> Set(a, b, c),
        12L -> Set(a, b, c)
      ),
      peerQuality = Map(d -> (5, 5), e -> (5, 5)),
      activeScores = Map(a -> 120, b -> 120, c -> 120, d -> 60, e -> 60),
      minActiveSize = 3,
      targetActiveSize = 5,
      maxActiveSize = 5,
      maxExpansionPerRound = 1,
      minProbationReentrySlots = 2
    )

    val probationSet = result.probationAdmitted.toSet
    val recentCore = result.active.take(result.recentSignerPoolSize).toSet

    expect.same(3, result.recentSignerPoolSize) &&
    expect.same(Set(d, e), probationSet) &&
    expect.same(Set(a, b, c), recentCore) &&
    expect(probationSet.intersect(recentCore).isEmpty)
  }

  pureTest("promoted reserves maintain active target when expansion cadence is closed") {
    val result = fromRecent(
      selected = List(a, b, c, d),
      recentSigners = window(
        10L -> Set(a, b, c),
        11L -> Set(a, b, c),
        12L -> Set(a, b, c)
      ),
      peerQuality = Map(d -> (5, 5)),
      activeScores = Map(a -> 80, b -> 80, c -> 80, d -> 120),
      targetActiveSize = 4,
      maxExpansionPerRound = 0
    )

    expect.same(List(a, b, c, d), result.active) &&
    expect.same(0, result.expansionAdmittedSize) &&
    expect.same(1, result.reserveAdmittedSize) &&
    expect.same(List(d), result.reserveAdmitted) &&
    expect(result.exclusions.isEmpty)
  }

  pureTest("recent signers below retain threshold yield active slots to promoted candidates") {
    val result = fromRecent(
      selected = List(a, b, c, d),
      recentSigners = window(
        10L -> Set(a, b, c),
        11L -> Set(a, b, c),
        12L -> Set(a, b, c)
      ),
      peerQuality = Map(d -> (5, 5)),
      activeScores = Map(a -> 80, b -> 80, c -> 60, d -> 120),
      targetActiveSize = 3
    )

    expect.same(List(a, b, d), result.active) &&
    expect.same(Set(c), result.exclusions.collect { case e if e.reason == ExclusionReason.ScoreBelowRetainThreshold => e.peerId }.toSet) &&
    expect.same(1, result.expansionAdmittedSize)
  }

  pureTest("below-retain recent signers stay active when filtering would break active floor") {
    val result = fromRecent(
      selected = List(a, b),
      recentSigners = window(
        10L -> Set(a, b),
        11L -> Set(a, b),
        12L -> Set(a, b)
      ),
      activeScores = Map(a -> 80, b -> 60),
      minActiveSize = 2,
      targetActiveSize = 3
    )

    expect.same(List(a, b), result.active) &&
    expect(result.exclusions.isEmpty) &&
    expect(!result.recentFilterApplied)
  }

  pureTest("controller separates cadence expansion from promoted reserve maintenance") {
    val result = fromRecent(
      selected = List(a, b, c, d),
      recentSigners = window(
        10L -> Set(a, b),
        11L -> Set(a, b),
        12L -> Set(a, b)
      ),
      peerQuality = Map(c -> (5, 5), d -> (5, 5)),
      activeScores = Map(a -> 80, b -> 80, c -> 130, d -> 120),
      targetActiveSize = 4,
      maxExpansionPerRound = 1
    )

    expect.same(List(a, b, c, d), result.active) &&
    expect(result.exclusions.isEmpty) &&
    expect.same(1, result.expansionAdmittedSize) &&
    expect.same(1, result.reserveAdmittedSize) &&
    expect.same(List(d), result.reserveAdmitted)
  }

  pureTest("controller score integrates finalized participation and self-health") {
    val scores = ConsensusPeerController.advanceScores(
      prior = SortedMap(a -> 80, b -> 80, c -> 80),
      evidence = ConsensusPeerController.RoundEvidence(
        roundStart = Set(a, b, c),
        completed = Set(a, b),
        responders = Set(a),
        evicted = Set(c),
        observedSelfHealth = SortedMap(b -> SelfHealthHint.Degraded)
      ),
      config = ConsensusPeerController.Config.default
    )

    expect.same(Some(104), scores.get(a)) &&
    expect.same(Some(79), scores.get(b)) &&
    expect.same(Some(24), scores.get(c))
  }

  pureTest("controller penalizes active peers missing from finalized timeout certificate voters") {
    val scores = ConsensusPeerController.advanceScores(
      prior = SortedMap(a -> 80, b -> 80, c -> 80),
      evidence = ConsensusPeerController.RoundEvidence(
        roundStart = Set(a, b, c),
        completed = Set(a, b, c),
        responders = Set(a, b, c),
        timeoutVoters = Set(a, c),
        evicted = Set.empty,
        observedSelfHealth = SortedMap.empty
      ),
      config = ConsensusPeerController.Config.default
    )

    expect.same(Some(104), scores.get(a)) &&
    expect.same(Some(94), scores.get(b)) &&
    expect.same(Some(104), scores.get(c))
  }

  pureTest("certified timeout shrink retains timeout voters when floor is satisfied") {
    val result = ActiveFacilitatorAdmission.fromCertifiedTimeout(
      selected = List(a, b, c),
      recentSigners = window(
        10L -> Set(a, b, c),
        11L -> Set(a, b, c),
        12L -> Set(a, b, c)
      ),
      timeoutVoters = Set(a, c),
      minActiveSize = 2
    )

    expect.same(List(a, c), result.active) &&
    expect.same(Set(b), result.exclusions.collect { case e if e.reason == ExclusionReason.CertifiedTimeoutMissing => e.peerId }.toSet) &&
    expect(result.recentFilterApplied)
  }

  pureTest("certified timeout shrink can retain reserve voters from selected pool") {
    val result = ActiveFacilitatorAdmission.fromCertifiedTimeout(
      selected = List(a, b, c, d),
      recentSigners = SortedMap.empty,
      timeoutVoters = Set(a, d),
      minActiveSize = 2
    )

    expect.same(List(a, d), result.active) &&
    expect.same(Set(b, c), result.exclusions.collect { case e if e.reason == ExclusionReason.CertifiedTimeoutMissing => e.peerId }.toSet) &&
    expect(result.recentFilterApplied)
  }

  pureTest("certified timeout shrink fills from recent signers to preserve floor") {
    val result = ActiveFacilitatorAdmission.fromCertifiedTimeout(
      selected = List(a, b, c, d),
      recentSigners = window(
        10L -> Set(a, b, c),
        11L -> Set(a, b, c),
        12L -> Set(a, b, c)
      ),
      timeoutVoters = Set(c),
      minActiveSize = 3
    )

    expect.same(List(c, a, b), result.active) &&
    expect.same(Set(d), result.exclusions.collect { case e if e.reason == ExclusionReason.CertifiedTimeoutMissing => e.peerId }.toSet) &&
    expect(result.recentFilterApplied)
  }

  pureTest("certified timeout shrink is bypassed when retained set cannot satisfy floor") {
    val result = ActiveFacilitatorAdmission.fromCertifiedTimeout(
      selected = List(a, b, c),
      recentSigners = window(
        10L -> Set(a),
        11L -> Set(a),
        12L -> Set(a)
      ),
      timeoutVoters = Set(a),
      minActiveSize = 2
    )

    expect.same(List(a, b, c), result.active) &&
    expect(result.exclusions.isEmpty) &&
    expect(!result.recentFilterApplied)
  }

  // Tier-1 stickiness (recentSignerWindow): a peer that last signed several ordinals ago (present in
  // a 10-deep lookback, absent from the default 3-deep one) and still holds retain score is EXCLUDED
  // from the recent-signer pool with the narrow window but INCLUDED once the lookback is widened --
  // the change that keeps intermittently-signing Tier-1 peers in the paid committee instead of
  // churning them through the volatile expansion/reserve fill.
  pureTest("widening recentSignerWindow keeps an intermittently-signing peer in the recent-signer pool") {
    val signers = window(
      10L -> Set(a, b, c, d),
      11L -> Set(a, b, c),
      12L -> Set(a, b, c),
      13L -> Set(a, b, c),
      14L -> Set(a, b, c),
      15L -> Set(a, b, c),
      16L -> Set(a, b, c),
      17L -> Set(a, b, c),
      18L -> Set(a, b, c),
      19L -> Set(a, b, c)
    )
    val scores = Map(a -> 120, b -> 120, c -> 120, d -> 80)

    val narrow = fromRecent(
      selected = List(a, b, c, d),
      recentSigners = signers,
      activeScores = scores,
      minActiveSize = 3,
      targetActiveSize = 3,
      maxActiveSize = 4,
      recentSignerWindow = 3
    )
    val wide = fromRecent(
      selected = List(a, b, c, d),
      recentSigners = signers,
      activeScores = scores,
      minActiveSize = 3,
      targetActiveSize = 3,
      maxActiveSize = 4,
      recentSignerWindow = 10
    )

    expect(!narrow.active.contains(d)) &&
    expect(wide.active.contains(d)) &&
    expect.same(List(a, b, c, d), wide.active)
  }

  // Clamp (Codex review #2): a recentSignerWindow below the demotion-hysteresis floor is raised to it
  // so it cannot make the window "not deep enough" and silently disable the recent-signer path. With
  // a 3-deep signer history and recentSignerWindow = 0, the filter still applies (floored to 3).
  pureTest("recentSignerWindow is floored to the demotion-hysteresis depth") {
    val floored = fromRecent(
      selected = List(a, b, c, d),
      recentSigners = window(
        10L -> Set(a, b, c),
        11L -> Set(a, b, c),
        12L -> Set(a, b, c)
      ),
      peerQuality = Map(d -> (1, 5)),
      targetActiveSize = 3,
      recentSignerWindow = 0
    )

    expect(floored.recentFilterApplied) &&
    expect.same(List(a, b, c), floored.active)
  }

  // expansionAllowedAtOrdinal is the single source of truth for the expansion cadence: the
  // StateCreators gate the actual admission on it and the StallDetector gates expansion-candidate
  // AdmissionVote emission on it, so votes are only spread on rounds where expansion can be applied.

  pureTest("expansionAllowedAtOrdinal admits expansion only on multiples of the interval") {
    val interval = 5
    val allowed = (0L to 12L).toList.filter(ActiveFacilitatorAdmission.expansionAllowedAtOrdinal(_, interval))
    expect
      .same(List(0L, 5L, 10L), allowed)
      .and(expect(!ActiveFacilitatorAdmission.expansionAllowedAtOrdinal(4L, interval), "ordinal 4 is not a multiple of 5"))
      .and(expect(ActiveFacilitatorAdmission.expansionAllowedAtOrdinal(5L, interval), "ordinal 5 is a multiple of 5"))
  }

  pureTest("expansionAllowedAtOrdinal with interval 1 admits expansion on every round") {
    expect(
      (0L to 6L).forall(ActiveFacilitatorAdmission.expansionAllowedAtOrdinal(_, 1)),
      "interval 1 allows expansion on every ordinal"
    )
  }

  pureTest("expansionAllowedAtOrdinal floors a non-positive interval to 1 so expansion is never disabled") {
    expect(
      (0L to 4L).forall(ActiveFacilitatorAdmission.expansionAllowedAtOrdinal(_, 0)),
      "interval 0 floors to 1 -> allowed every round"
    ).and(
      expect(
        (0L to 4L).forall(ActiveFacilitatorAdmission.expansionAllowedAtOrdinal(_, -3)),
        "negative interval floors to 1 -> allowed every round"
      )
    )
  }
}
