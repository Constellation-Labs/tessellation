package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

/** Locks in the v19/v22 tier transition contract. Every assertion encodes one rule from `docs/consensus/multi-committee-architecture.md`;
  * do not weaken these without re-reading the design doc.
  *
  * v22 change: demotion is now gated by CONSECUTIVE-miss hysteresis (`DemotionConsecutiveMisses`) read from the rolling `recentSigners`
  * window, not a single missed signature. A Core peer demotes only after sustained silence; a peer that signs the current round (present in
  * the latest window entry) is never demoted, which protects freshly-joined and post-rollback peers.
  */
object TierTransitionsSuite extends SimpleIOSuite {

  import TierTransitions.{Core, DemotionConsecutiveMisses, Tier1, Witness, computeNextTier, computeNextTiers}

  private def pid(name: String): PeerId =
    PeerId(Hex(name.getBytes("UTF-8").map(b => f"$b%02x").mkString))

  private def ord(n: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(n)

  /** Build a recentSigners window from `(ordinal, signers)` entries. Highest ordinal = most recent round (the just-completed one). */
  private def window(entries: (Long, Set[PeerId])*): SortedMap[SnapshotOrdinal, SortedSet[PeerId]] =
    SortedMap.from(entries.map { case (o, ps) => ord(o) -> SortedSet.from(ps) })

  // === single-peer computeNextTier ===

  pureTest("bootstrap default: priorTier=None resolves to Tier 2 (Core)") {
    expect.same(
      Core,
      computeNextTier(priorTier = None, wasInRoundStart = false, missedRecentConsecutive = false, roundCompleted = true)
    )
  }

  pureTest("Tier 2 -> Tier 1 demote: completed round, in roundStart, missed the recent consecutive window") {
    expect.same(
      Tier1,
      computeNextTier(priorTier = Some(Core), wasInRoundStart = true, missedRecentConsecutive = true, roundCompleted = true)
    )
  }

  pureTest("Tier 2 stays Core: completed round, in roundStart, did NOT miss the recent window (signed recently)") {
    expect.same(
      Core,
      computeNextTier(priorTier = Some(Core), wasInRoundStart = true, missedRecentConsecutive = false, roundCompleted = true)
    )
  }

  pureTest("Tier 2 stays Core: completed round, NOT in roundStart (peer skipped the round)") {
    expect.same(
      Core,
      computeNextTier(priorTier = Some(Core), wasInRoundStart = false, missedRecentConsecutive = true, roundCompleted = true)
    )
  }

  pureTest("Failed round NEVER cascades: priorTier preserved when roundCompleted=false") {
    // A Tier 2 peer in the roundStart of a FAILED round must NOT be demoted even if it missed the
    // recent window; otherwise a stretch of failed rounds would collapse Core.
    val nextCore =
      computeNextTier(priorTier = Some(Core), wasInRoundStart = true, missedRecentConsecutive = true, roundCompleted = false)
    val nextTier1 =
      computeNextTier(priorTier = Some(Tier1), wasInRoundStart = true, missedRecentConsecutive = true, roundCompleted = false)
    val nextWitness =
      computeNextTier(priorTier = Some(Witness), wasInRoundStart = false, missedRecentConsecutive = false, roundCompleted = false)
    expect.same((Core, Tier1, Witness), (nextCore, nextTier1, nextWitness))
  }

  pureTest("Tier 1 is sticky: does not promote back to Core via this function") {
    // Re-promotion happens via CommitteeBuilder selecting from the wider pool when Core falls below
    // its floor, not via a tier upgrade here.
    expect.same(
      Tier1,
      computeNextTier(priorTier = Some(Tier1), wasInRoundStart = true, missedRecentConsecutive = false, roundCompleted = true)
    )
  }

  pureTest("Witness tier sticks across rounds (open membership, observation only)") {
    expect.same(
      Witness,
      computeNextTier(priorTier = Some(Witness), wasInRoundStart = false, missedRecentConsecutive = false, roundCompleted = true)
    )
  }

  // === batch computeNextTiers (windowed consecutive-miss hysteresis) ===

  pureTest("failed round is a no-op regardless of window") {
    val a = pid("aaaa")
    val b = pid("bbbb")
    val priorTiers = SortedMap[PeerId, Int](a -> Core, b -> Core)
    val roundStart = Set[PeerId](a, b)
    // b absent from a deep window, but the round failed -> no demotion.
    val w = window(10L -> Set(a), 11L -> Set(a), 12L -> Set(a))
    expect.same(priorTiers, computeNextTiers(priorTiers, roundStart, w, roundCompleted = false))
  }

  pureTest("hysteresis: shallow window (fewer than DemotionConsecutiveMisses entries) never demotes") {
    // Bootstrap fallback: until the window is deep enough, a non-signer is NOT shed -- this is what
    // protects the first rounds after a cold restart and freshly-joined peers.
    val a = pid("aaaa")
    val b = pid("bbbb")
    val priorTiers = SortedMap[PeerId, Int](a -> Core, b -> Core)
    val roundStart = Set[PeerId](a, b)
    // Only 2 entries (< 3), b never signed -> still no demotion.
    val w = window(10L -> Set(a), 11L -> Set(a))
    expect(window().isEmpty) // sanity: empty window builder
      .and(expect.same(priorTiers, computeNextTiers(priorTiers, roundStart, w, roundCompleted = true)))
  }

  pureTest("hysteresis: deep window, absent from ALL of the most-recent D entries -> demote") {
    val a = pid("aaaa")
    val b = pid("bbbb")
    val priorTiers = SortedMap[PeerId, Int](a -> Core, b -> Core)
    val roundStart = Set[PeerId](a, b)
    // 3 entries (== D), b absent from all three -> sustained silence -> demote.
    val w = window(10L -> Set(a), 11L -> Set(a), 12L -> Set(a))
    expect.same(SortedMap[PeerId, Int](a -> Core, b -> Tier1), computeNextTiers(priorTiers, roundStart, w, roundCompleted = true))
  }

  pureTest("hysteresis: signed the CURRENT (latest) round -> never demoted, even if it missed earlier") {
    // The just-completed round is the highest-ordinal entry. A peer present there cannot be
    // absent-from-all-recent. This is the freshly-joined / rejoiner protection.
    val a = pid("aaaa")
    val b = pid("bbbb")
    val priorTiers = SortedMap[PeerId, Int](a -> Core, b -> Core)
    val roundStart = Set[PeerId](a, b)
    // b missed 10 and 11 but signed 12 (current) -> kept.
    val w = window(10L -> Set(a), 11L -> Set(a), 12L -> Set(a, b))
    expect.same(priorTiers, computeNextTiers(priorTiers, roundStart, w, roundCompleted = true))
  }

  pureTest("hysteresis: signed one of the recent D rounds (not all missed) -> kept") {
    val a = pid("aaaa")
    val b = pid("bbbb")
    val priorTiers = SortedMap[PeerId, Int](a -> Core, b -> Core)
    val roundStart = Set[PeerId](a, b)
    // b signed the middle of the last 3 -> not absent-from-all -> kept.
    val w = window(10L -> Set(a), 11L -> Set(a, b), 12L -> Set(a))
    expect.same(priorTiers, computeNextTiers(priorTiers, roundStart, w, roundCompleted = true))
  }

  pureTest("hysteresis: only the most-recent D entries count; an old signature does not save a now-silent peer") {
    val a = pid("aaaa")
    val b = pid("bbbb")
    val priorTiers = SortedMap[PeerId, Int](a -> Core, b -> Core)
    val roundStart = Set[PeerId](a, b)
    // b signed ordinal 9 (oldest) but is absent from the most-recent 3 (10, 11, 12) -> demote.
    val w = window(9L -> Set(a, b), 10L -> Set(a), 11L -> Set(a), 12L -> Set(a))
    expect.same(SortedMap[PeerId, Int](a -> Core, b -> Tier1), computeNextTiers(priorTiers, roundStart, w, roundCompleted = true))
  }

  pureTest("a peer not in roundStart is never demoted (was not expected to sign)") {
    val a = pid("aaaa")
    val b = pid("bbbb")
    val priorTiers = SortedMap[PeerId, Int](a -> Core, b -> Core)
    val roundStart = Set[PeerId](a) // b not in roundStart this round
    val w = window(10L -> Set(a), 11L -> Set(a), 12L -> Set(a))
    expect.same(priorTiers, computeNextTiers(priorTiers, roundStart, w, roundCompleted = true))
  }

  pureTest("a peer absent from priorTiers and roundStart is absent from the result") {
    val a = pid("aaaa")
    val b = pid("bbbb")
    val priorTiers = SortedMap[PeerId, Int](a -> Core)
    val roundStart = Set[PeerId](a)
    val w = window(10L -> Set(a), 11L -> Set(a), 12L -> Set(a))
    val next = computeNextTiers(priorTiers, roundStart, w, roundCompleted = true)
    expect(!next.contains(b)).and(expect.same(SortedMap[PeerId, Int](a -> Core), next))
  }

  pureTest("bootstrap-Core peer (None prior) with sustained silence IS demoted") {
    val a = pid("aaaa")
    val priorTiers = SortedMap.empty[PeerId, Int]
    val roundStart = Set[PeerId](a)
    val w = window(10L -> Set.empty[PeerId], 11L -> Set.empty[PeerId], 12L -> Set.empty[PeerId])
    expect.same(SortedMap[PeerId, Int](a -> Tier1), computeNextTiers(priorTiers, roundStart, w, roundCompleted = true))
  }

  pureTest("Witness peer not in roundStart stays Witness on completed round") {
    val a = pid("aaaa")
    val priorTiers = SortedMap[PeerId, Int](a -> Witness)
    val roundStart = Set.empty[PeerId]
    val w = window(10L -> Set.empty[PeerId], 11L -> Set.empty[PeerId], 12L -> Set.empty[PeerId])
    expect.same(SortedMap[PeerId, Int](a -> Witness), computeNextTiers(priorTiers, roundStart, w, roundCompleted = true))
  }

  pureTest("the tier-aware overload persists the actual Core and Tier-1 round seats") {
    val core = pid("core")
    val existingTier1 = pid("tier1")
    val newlyAdmittedTier1 = pid("new-tier1")
    val priorTiers = SortedMap[PeerId, Int](core -> Core, existingTier1 -> Tier1)
    val roundStart = Set(core, existingTier1, newlyAdmittedTier1)
    val coreSet = Set(core)
    val w = window(
      10L -> roundStart,
      11L -> roundStart,
      12L -> roundStart
    )

    val next = computeNextTiers(priorTiers, roundStart, coreSet, w, roundCompleted = true)

    expect.same(Some(Core), next.get(core)) &&
    expect.same(Some(Tier1), next.get(existingTier1)) &&
    expect.same(Some(Tier1), next.get(newlyAdmittedTier1))
  }

  pureTest("DemotionConsecutiveMisses is the documented hysteresis depth") {
    // Guard: if someone tunes the constant, this test makes the change explicit in review.
    expect.same(3, DemotionConsecutiveMisses)
  }

  pureTest("large committee, mixed outcomes under the window") {
    val peers = (1 to 7).map(i => pid(f"peer$i%04d"))
    val Seq(p1, p2, p3, p4, p5, p6, p7) = peers
    val priorTiers = SortedMap[PeerId, Int](
      p1 -> Core,
      p2 -> Core,
      p3 -> Core,
      p4 -> Tier1,
      p5 -> Witness,
      p6 -> Core,
      p7 -> Core
    )
    val roundStart = Set[PeerId](p1, p2, p3, p6, p7)
    // Last 3 rounds: p3 silent in all three (demote); p7 silent in 11,12 but signed the latest (kept).
    val w = window(
      10L -> Set(p1, p2, p6, p7),
      11L -> Set(p1, p2, p6),
      12L -> Set(p1, p2, p6, p7)
    )
    val expected = SortedMap[PeerId, Int](
      p1 -> Core,
      p2 -> Core,
      p3 -> Tier1, // absent from all of last 3 -> demoted
      p4 -> Tier1, // unchanged (not Core)
      p5 -> Witness, // unchanged
      p6 -> Core,
      p7 -> Core // signed the latest round -> kept despite a miss
    )
    expect.same(expected, computeNextTiers(priorTiers, roundStart, w, roundCompleted = true))
  }
}
