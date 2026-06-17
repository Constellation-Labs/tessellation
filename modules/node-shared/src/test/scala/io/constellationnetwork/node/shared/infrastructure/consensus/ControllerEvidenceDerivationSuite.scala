package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.infrastructure.selfhealth.SelfHealthHint
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{ControllerEvidenceEntry, SnapshotOrdinal}
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

object ControllerEvidenceDerivationSuite extends SimpleIOSuite {

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))
  private def ord(n: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(n)

  private val a = peer('a')
  private val b = peer('b')
  private val c = peer('c')
  private val d = peer('d')
  private val e = peer('e')

  private def entry(
    roundStart: Set[PeerId],
    signers: Set[PeerId],
    timeoutVoters: Set[PeerId] = Set.empty,
    admitted: Set[PeerId] = Set.empty,
    evicted: Set[PeerId] = Set.empty
  ): ControllerEvidenceEntry =
    ControllerEvidenceEntry(
      roundStartFacilitators = SortedSet.from(roundStart),
      completedSigners = SortedSet.from(signers),
      timeoutVoters = SortedSet.from(timeoutVoters),
      admittedPeers = SortedSet.from(admitted),
      evictedPeers = SortedSet.from(evicted)
    )

  private def window(entries: (Long, ControllerEvidenceEntry)*): SortedMap[SnapshotOrdinal, ControllerEvidenceEntry] =
    SortedMap.from(entries.toList.map { case (o, en) => ord(o) -> en })

  private def fullWindow(size: Int, roundStart: Set[PeerId], signers: Set[PeerId]): SortedMap[SnapshotOrdinal, ControllerEvidenceEntry] =
    window((1L to size.toLong).map(o => o -> entry(roundStart, signers)): _*)

  pureTest("empty evidence derives an empty map") {
    expect.same(
      SortedMap.empty[PeerId, ControllerEvidenceDerivation.DerivedPeerState],
      ControllerEvidenceDerivation.derive(SortedMap.empty)
    )
  }

  pureTest("a fully participating peer saturates above the promote threshold within one window") {
    val derived = ControllerEvidenceDerivation.derive(fullWindow(10, Set(a, b), Set(a, b)))

    expect.same(ControllerEvidenceDerivation.MaxScore, derived(a).derivedScore) &&
    expect(derived(a).derivedScore >= 100)
  }

  pureTest("a fully participating peer crosses the promote threshold after five entries") {
    val derived = ControllerEvidenceDerivation.derive(fullWindow(5, Set(a, b), Set(a, b)))

    expect.same(100, derived(a).derivedScore)
  }

  pureTest("misses subtract MissWeight for roundStart-but-not-signed entries") {
    val evidence = window(
      1L -> entry(Set(a, b), Set(a, b)),
      2L -> entry(Set(a, b), Set(a, b)),
      3L -> entry(Set(a, b), Set(a)),
      4L -> entry(Set(a, b), Set(a))
    )
    val derived = ControllerEvidenceDerivation.derive(evidence)

    // b: 2 signs (+40) and 2 misses (-30) = 10
    expect.same(10, derived(b).derivedScore) &&
    expect.same(80, derived(a).derivedScore)
  }

  pureTest("score is clamped at MinScore for chronic missers") {
    val evidence = window(
      1L -> entry(Set(a, b), Set(a)),
      2L -> entry(Set(a, b), Set(a)),
      3L -> entry(Set(a, b), Set(a))
    )
    val derived = ControllerEvidenceDerivation.derive(evidence)

    expect.same(ControllerEvidenceDerivation.MinScore, derived(b).derivedScore)
  }

  pureTest("certified appearances add CertWeight per admission and per timeout vote") {
    val evidence = window(
      1L -> entry(Set(a, b), Set(a, b), timeoutVoters = Set(a), admitted = Set(c))
    )
    val derived = ControllerEvidenceDerivation.derive(evidence)

    // a: sign (+20) + timeout vote (+10) = 30; b: sign only = 20; c: admitted only = 10
    expect.same(30, derived(a).derivedScore) &&
    expect.same(20, derived(b).derivedScore) &&
    expect.same(10, derived(c).derivedScore)
  }

  pureTest("derivedQuality counts completed and participated entries") {
    val evidence = window(
      1L -> entry(Set(a, b), Set(a, b)),
      2L -> entry(Set(a, b), Set(a)),
      3L -> entry(Set(a), Set(a))
    )
    val derived = ControllerEvidenceDerivation.derive(evidence)

    expect.same((3, 3), derived(a).derivedQuality) &&
    expect.same((1, 2), derived(b).derivedQuality)
  }

  pureTest("a certificate-only appearance yields a key with zero participation") {
    val evidence = window(1L -> entry(Set(a), Set(a), timeoutVoters = Set(e)))
    val derived = ControllerEvidenceDerivation.derive(evidence)

    expect.same((0, 0), derived(e).derivedQuality) &&
    expect.same(ControllerEvidenceDerivation.CertWeight, derived(e).derivedScore)
  }

  pureTest("shallow window keeps every peer at Core (bootstrap regime, no demotion)") {
    val evidence = window(
      1L -> entry(Set(a, b), Set(a)),
      2L -> entry(Set(a, b), Set(a))
    )
    val derived = ControllerEvidenceDerivation.derive(evidence)

    expect.same(TierTransitions.Core, derived(a).derivedTier) &&
    expect.same(TierTransitions.Core, derived(b).derivedTier)
  }

  pureTest("deep window demotes a peer absent from all recent signer sets to Tier1") {
    val evidence = window(
      1L -> entry(Set(a, b), Set(a, b)),
      2L -> entry(Set(a, b), Set(a)),
      3L -> entry(Set(a, b), Set(a)),
      4L -> entry(Set(a, b), Set(a))
    )
    val derived = ControllerEvidenceDerivation.derive(evidence)

    // b signed only at ordinal 1, which has rotated out of the recent
    // DemotionConsecutiveMisses (3) signer sets.
    expect.same(TierTransitions.Tier1, derived(b).derivedTier) &&
    expect.same(TierTransitions.Core, derived(a).derivedTier)
  }

  pureTest("signing any of the recent signer sets retains Core") {
    val evidence = window(
      1L -> entry(Set(a, b), Set(a)),
      2L -> entry(Set(a, b), Set(a, b)),
      3L -> entry(Set(a, b), Set(a)),
      4L -> entry(Set(a, b), Set(a))
    )
    val derived = ControllerEvidenceDerivation.derive(evidence)

    // b signed at ordinal 2, which is within the last 3 entries (2, 3, 4).
    expect.same(TierTransitions.Core, derived(b).derivedTier)
  }

  // ===========================================================================
  // consecutiveMisses / chronicMisses (chronic-core replacement inputs)
  // ===========================================================================

  pureTest("consecutiveMisses counts the trailing asked-but-silent streak") {
    val evidence = window(
      1L -> entry(Set(a, b), Set(a, b)),
      2L -> entry(Set(a, b), Set(a)),
      3L -> entry(Set(a, b), Set(a)),
      4L -> entry(Set(a, b), Set(a))
    )

    expect.same(3, ControllerEvidenceDerivation.consecutiveMisses(evidence, b)) &&
    expect.same(0, ControllerEvidenceDerivation.consecutiveMisses(evidence, a))
  }

  pureTest("consecutiveMisses resets to zero when the peer signs the latest entry") {
    val evidence = window(
      1L -> entry(Set(a, b), Set(a)),
      2L -> entry(Set(a, b), Set(a)),
      3L -> entry(Set(a, b), Set(a, b))
    )

    expect.same(0, ControllerEvidenceDerivation.consecutiveMisses(evidence, b))
  }

  pureTest("consecutiveMisses: absence from roundStart breaks the streak rather than extending it") {
    // b misses at 1 and 2, is NOT asked to sign at 3 (absent from roundStart), then
    // misses again at 4. The documented choice: absence breaks the streak (the peer
    // was not asked to sign, so the entry is no evidence of unresponsiveness), so only
    // the trailing miss at 4 counts.
    val evidence = window(
      1L -> entry(Set(a, b), Set(a)),
      2L -> entry(Set(a, b), Set(a)),
      3L -> entry(Set(a), Set(a)),
      4L -> entry(Set(a, b), Set(a))
    )

    expect.same(1, ControllerEvidenceDerivation.consecutiveMisses(evidence, b))
  }

  pureTest("consecutiveMisses is zero on empty evidence and for peers never in roundStart") {
    val evidence = window(1L -> entry(Set(a), Set(a)))

    expect.same(0, ControllerEvidenceDerivation.consecutiveMisses(SortedMap.empty, b)) &&
    expect.same(0, ControllerEvidenceDerivation.consecutiveMisses(evidence, b))
  }

  pureTest("chronicMisses flags only peers at or above ChronicMissThreshold") {
    // b: 3 trailing misses (chronic at threshold 3); c: 2 trailing misses (not chronic);
    // a: signs everything.
    val evidence = window(
      1L -> entry(Set(a, b), Set(a, b)),
      2L -> entry(Set(a, b), Set(a)),
      3L -> entry(Set(a, b, c), Set(a)),
      4L -> entry(Set(a, b, c), Set(a))
    )

    expect.same(SortedMap(b -> 3), ControllerEvidenceDerivation.chronicMisses(evidence))
  }

  pureTest("chronicMisses is empty on an empty window") {
    expect.same(SortedMap.empty[PeerId, Int], ControllerEvidenceDerivation.chronicMisses(SortedMap.empty))
  }

  pureTest("appendBounded appends the entry and trims to the tightening window") {
    val prior = fullWindow(10, Set(a), Set(a))
    val next = ControllerEvidenceDerivation.appendBounded(prior, ord(11L), entry(Set(a, b), Set(b)), tighteningWindow = 10)

    expect.same(10, next.size) &&
    expect.same((2L to 11L).map(ord).toList, next.keys.toList) &&
    expect.same(SortedSet(b), next(ord(11L)).completedSigners)
  }

  pureTest("appendBounded replaces an existing entry at the same key") {
    val prior = window(1L -> entry(Set(a), Set(a)))
    val next = ControllerEvidenceDerivation.appendBounded(prior, ord(1L), entry(Set(b), Set(b)), tighteningWindow = 10)

    expect.same(1, next.size) &&
    expect.same(SortedSet(b), next(ord(1L)).completedSigners)
  }

  pureTest("nextPenaltyUntil anchors a certified eviction at currentOrdinal plus duration") {
    val next = ControllerEvidenceDerivation.nextPenaltyUntil(
      prior = SortedMap.empty,
      certifiedEvictions = Set(a),
      certifiedAdmissions = Set.empty,
      currentOrdinal = ord(50L),
      penaltyDurationOrdinals = 100
    )

    expect.same(SortedMap(a -> ord(150L)), next)
  }

  pureTest("nextPenaltyUntil clears an entry on certified admission, including same-round eviction") {
    val next = ControllerEvidenceDerivation.nextPenaltyUntil(
      prior = SortedMap(a -> ord(200L)),
      certifiedEvictions = Set(b),
      certifiedAdmissions = Set(a, b),
      currentOrdinal = ord(50L),
      penaltyDurationOrdinals = 100
    )

    expect.same(SortedMap.empty[PeerId, SnapshotOrdinal], next)
  }

  pureTest("nextPenaltyUntil carries unexpired entries unchanged and drops expired ones") {
    val next = ControllerEvidenceDerivation.nextPenaltyUntil(
      prior = SortedMap(a -> ord(51L), b -> ord(50L), c -> ord(10L)),
      certifiedEvictions = Set.empty,
      certifiedAdmissions = Set.empty,
      currentOrdinal = ord(50L),
      penaltyDurationOrdinals = 100
    )

    // a (until 51 > 50) survives untouched; b (until == current) and c (stale) expire.
    expect.same(SortedMap(a -> ord(51L)), next)
  }

  pureTest("nextPenaltyUntil clamps a non-positive duration to zero") {
    val next = ControllerEvidenceDerivation.nextPenaltyUntil(
      prior = SortedMap.empty,
      certifiedEvictions = Set(a),
      certifiedAdmissions = Set.empty,
      currentOrdinal = ord(50L),
      penaltyDurationOrdinals = -7
    )

    expect.same(SortedMap(a -> ord(50L)), next)
  }

  pureTest("derivation is deterministic for the same evidence window") {
    val evidence = window(
      1L -> entry(Set(a, b, c), Set(a, b), timeoutVoters = Set(a), evicted = Set(d)),
      2L -> entry(Set(a, b), Set(a), admitted = Set(d)),
      3L -> entry(Set(a, b, d), Set(a, d)),
      4L -> entry(Set(a, b, d), Set(a, d))
    )

    expect.same(ControllerEvidenceDerivation.derive(evidence), ControllerEvidenceDerivation.derive(evidence))
  }

  // ===========================================================================
  // Stage 4: controllerInputsWithFallback (the StateCreators' read-side switch)
  // ===========================================================================

  private val carriedScores: Map[PeerId, Int] = Map(a -> 11, b -> 22)
  private val carriedQuality: Map[PeerId, (Int, Int)] = Map(a -> (1, 2), b -> (3, 4))
  private val carriedTiers: SortedMap[PeerId, Int] = SortedMap(a -> TierTransitions.Core, b -> TierTransitions.Tier1)
  private val carriedViewChanges: Map[PeerId, Long] = Map(a -> 1L, b -> 7L)
  private val carriedSelfHealth: Map[PeerId, SelfHealthHint] = Map(a -> SelfHealthHint.Healthy, b -> SelfHealthHint.Degraded)

  pureTest("controllerInputsWithFallback returns the carried maps unchanged on an empty window") {
    val inputs = ControllerEvidenceDerivation.controllerInputsWithFallback(
      evidence = SortedMap.empty,
      carriedScores = carriedScores,
      carriedQuality = carriedQuality,
      carriedTiers = carriedTiers,
      carriedViewChanges = carriedViewChanges,
      carriedSelfHealth = carriedSelfHealth
    )

    expect.same(0, inputs.evidenceRounds) &&
    expect.same(carriedScores, inputs.activeScores) &&
    expect.same(carriedQuality, inputs.peerQuality) &&
    expect.same(carriedTiers, inputs.peerTiers) &&
    expect.same(carriedViewChanges, inputs.viewChanges) &&
    expect.same(carriedSelfHealth, inputs.selfHealth) &&
    expect.same(SortedMap.empty[PeerId, Int], inputs.chronicMisses) &&
    expect.same(Set.empty[PeerId], inputs.chronicallyMissing)
  }

  pureTest("controllerInputsWithFallback exposes evidence-derived chronicMisses in the evidence regime") {
    // b misses the trailing 4 entries (chronic); a signs everything.
    val evidence = window(
      1L -> entry(Set(a, b), Set(a, b)),
      2L -> entry(Set(a, b), Set(a)),
      3L -> entry(Set(a, b), Set(a)),
      4L -> entry(Set(a, b), Set(a)),
      5L -> entry(Set(a, b), Set(a))
    )
    val inputs = ControllerEvidenceDerivation.controllerInputsWithFallback(
      evidence = evidence,
      carriedScores = carriedScores,
      carriedQuality = carriedQuality,
      carriedTiers = carriedTiers,
      carriedViewChanges = carriedViewChanges,
      carriedSelfHealth = carriedSelfHealth
    )

    expect.same(SortedMap(b -> 4), inputs.chronicMisses) &&
    expect.same(Set(b), inputs.chronicallyMissing)
  }

  pureTest("controllerInputsWithFallback derives from evidence and ignores the carried maps when the window has entries") {
    val evidence = fullWindow(5, Set(a, b), Set(a, b))
    val inputs = ControllerEvidenceDerivation.controllerInputsWithFallback(
      evidence = evidence,
      carriedScores = carriedScores,
      carriedQuality = carriedQuality,
      carriedTiers = carriedTiers,
      carriedViewChanges = carriedViewChanges,
      carriedSelfHealth = carriedSelfHealth
    )
    val derived = ControllerEvidenceDerivation.derive(evidence)
    val derivedScores: Map[PeerId, Int] = derived.map { case (pid, s) => pid -> s.derivedScore }
    val derivedQuality: Map[PeerId, (Int, Int)] = derived.map { case (pid, s) => pid -> s.derivedQuality }
    val derivedTiers: SortedMap[PeerId, Int] = derived.map { case (pid, s) => pid -> s.derivedTier }

    expect.same(5, inputs.evidenceRounds) &&
    expect.same(derivedScores, inputs.activeScores) &&
    expect.same(derivedQuality, inputs.peerQuality) &&
    expect.same(derivedTiers, inputs.peerTiers)
  }

  pureTest("controllerInputsWithFallback emits empty viewChanges and selfHealth when evidence is present") {
    // These two are not yet evidence-derived; carrying them in the evidence regime would
    // reintroduce the seed-split divergence. Stage-5 gap items.
    val inputs = ControllerEvidenceDerivation.controllerInputsWithFallback(
      evidence = fullWindow(5, Set(a, b), Set(a, b)),
      carriedScores = carriedScores,
      carriedQuality = carriedQuality,
      carriedTiers = carriedTiers,
      carriedViewChanges = carriedViewChanges,
      carriedSelfHealth = carriedSelfHealth
    )

    expect(inputs.viewChanges.isEmpty) &&
    expect(inputs.selfHealth.isEmpty)
  }

  pureTest("controllerInputsWithFallback is a pure function of the evidence: divergent carried maps cannot change the derived branch") {
    val evidence = fullWindow(5, Set(a, b, c), Set(a, b))
    val inputsHealthy = ControllerEvidenceDerivation.controllerInputsWithFallback(
      evidence,
      carriedScores,
      carriedQuality,
      carriedTiers,
      carriedViewChanges,
      carriedSelfHealth
    )
    val inputsPoisoned = ControllerEvidenceDerivation.controllerInputsWithFallback(
      evidence,
      carriedScores = Map(a -> 0, c -> 150),
      carriedQuality = Map(c -> (9, 9)),
      carriedTiers = SortedMap(c -> TierTransitions.Core, b -> TierTransitions.Witness),
      carriedViewChanges = Map(b -> 99L),
      carriedSelfHealth = Map(b -> SelfHealthHint.Critical)
    )

    expect.same(inputsHealthy, inputsPoisoned)
  }
}
