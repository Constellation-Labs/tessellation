package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

/** Locks in the v19 tier transition contract. Every assertion encodes one rule from
  * `docs/consensus/multi-committee-architecture.md`; do not weaken these without re-reading
  * the design doc.
  */
object TierTransitionsSuite extends SimpleIOSuite {

  import TierTransitions.{Core, Tier1, Witness, computeNextTier, computeNextTiers}

  private def pid(name: String): PeerId =
    PeerId(Hex(name.getBytes("UTF-8").map(b => f"$b%02x").mkString))

  // === single-peer computeNextTier ===

  pureTest("bootstrap default: priorTier=None resolves to Tier 2 (Core)") {
    val next = computeNextTier(
      priorTier = None,
      wasInRoundStart = false,
      wasInRecentSigners = false,
      roundCompleted = true
    )
    expect.same(Core, next)
  }

  pureTest("Tier 2 -> Tier 1 demote: completed round, in roundStart, NOT in recentSigners") {
    val next = computeNextTier(
      priorTier = Some(Core),
      wasInRoundStart = true,
      wasInRecentSigners = false,
      roundCompleted = true
    )
    expect.same(Tier1, next)
  }

  pureTest("Tier 2 stays Core: completed round, in roundStart, in recentSigners") {
    val next = computeNextTier(
      priorTier = Some(Core),
      wasInRoundStart = true,
      wasInRecentSigners = true,
      roundCompleted = true
    )
    expect.same(Core, next)
  }

  pureTest("Tier 2 stays Core: completed round, NOT in roundStart (peer skipped the round)") {
    val next = computeNextTier(
      priorTier = Some(Core),
      wasInRoundStart = false,
      wasInRecentSigners = false,
      roundCompleted = true
    )
    expect.same(Core, next)
  }

  pureTest("Failed round NEVER cascades: priorTier preserved when roundCompleted=false") {
    // The critical invariant: a Tier 2 peer in the roundStart of a FAILED round must NOT
    // be demoted. Otherwise a single network flap during a round that would have failed
    // anyway would collapse the Core committee.
    val nextCore = computeNextTier(
      priorTier = Some(Core),
      wasInRoundStart = true,
      wasInRecentSigners = false,
      roundCompleted = false
    )
    val nextTier1 = computeNextTier(
      priorTier = Some(Tier1),
      wasInRoundStart = true,
      wasInRecentSigners = false,
      roundCompleted = false
    )
    val nextWitness = computeNextTier(
      priorTier = Some(Witness),
      wasInRoundStart = false,
      wasInRecentSigners = false,
      roundCompleted = false
    )
    expect.same((Core, Tier1, Witness), (nextCore, nextTier1, nextWitness))
  }

  pureTest("Failed round preserves bootstrap default (None resolves to Core, stays Core)") {
    val next = computeNextTier(
      priorTier = None,
      wasInRoundStart = true,
      wasInRecentSigners = false,
      roundCompleted = false
    )
    expect.same(Core, next)
  }

  pureTest("Tier 1 is sticky: does not promote back to Core just by signing") {
    // Demotion is round-by-round; promotion is NOT defined by computeNextTier (re-promotion
    // happens via CommitteeBuilder selecting from the wider pool when Core falls below
    // its floor, not via a tier upgrade here). A Tier 1 peer that signs the next round
    // stays Tier 1 in this function.
    val next = computeNextTier(
      priorTier = Some(Tier1),
      wasInRoundStart = true,
      wasInRecentSigners = true,
      roundCompleted = true
    )
    expect.same(Tier1, next)
  }

  pureTest("Witness tier sticks across rounds (open membership, observation only)") {
    val next = computeNextTier(
      priorTier = Some(Witness),
      wasInRoundStart = false,
      wasInRecentSigners = false,
      roundCompleted = true
    )
    expect.same(Witness, next)
  }

  // === batch computeNextTiers ===

  pureTest("computeNextTiers: only completed rounds update; failed round is a no-op") {
    val a = pid("aaaa")
    val b = pid("bbbb")
    val priorTiers = SortedMap[PeerId, Int](a -> Core, b -> Core)
    val roundStart = Set[PeerId](a, b)
    val signers = SortedSet[PeerId](b)
    val onFailed = computeNextTiers(priorTiers, roundStart, signers, roundCompleted = false)
    expect.same(priorTiers, onFailed)
  }

  pureTest("computeNextTiers: completed round demotes the missing signer") {
    val a = pid("aaaa")
    val b = pid("bbbb")
    val priorTiers = SortedMap[PeerId, Int](a -> Core, b -> Core)
    val roundStart = Set[PeerId](a, b)
    val signers = SortedSet[PeerId](b)
    val onCompleted = computeNextTiers(priorTiers, roundStart, signers, roundCompleted = true)
    expect.same(SortedMap[PeerId, Int](a -> Tier1, b -> Core), onCompleted)
  }

  pureTest("computeNextTiers: a peer absent from priorTiers and roundStart is absent from result") {
    val a = pid("aaaa")
    val b = pid("bbbb")
    val priorTiers = SortedMap[PeerId, Int](a -> Core)
    val roundStart = Set[PeerId](a)
    val signers = SortedSet[PeerId](a)
    val nextTiers = computeNextTiers(priorTiers, roundStart, signers, roundCompleted = true)
    expect(!nextTiers.contains(b)) and
      expect.same(SortedMap[PeerId, Int](a -> Core), nextTiers)
  }

  pureTest("computeNextTiers: a peer in roundStart but absent from priorTiers gets bootstrap-Core") {
    val a = pid("aaaa")
    val priorTiers = SortedMap.empty[PeerId, Int]
    val roundStart = Set[PeerId](a)
    val signers = SortedSet[PeerId](a)
    val nextTiers = computeNextTiers(priorTiers, roundStart, signers, roundCompleted = true)
    expect.same(SortedMap[PeerId, Int](a -> Core), nextTiers)
  }

  pureTest("computeNextTiers: bootstrap-Core peer who fails to sign IS demoted (treated as Core member)") {
    // Bootstrap default is Core, and computeNextTier treats `None` as Core. A peer that
    // was in roundStart with no prior classification is judged as a Core member; if they
    // do not appear in recentSigners on a completed round, they demote to Tier 1.
    val a = pid("aaaa")
    val priorTiers = SortedMap.empty[PeerId, Int]
    val roundStart = Set[PeerId](a)
    val signers = SortedSet.empty[PeerId]
    val nextTiers = computeNextTiers(priorTiers, roundStart, signers, roundCompleted = true)
    expect.same(SortedMap[PeerId, Int](a -> Tier1), nextTiers)
  }

  pureTest("computeNextTiers: Tier 0 (Witness) peer not in roundStart stays Witness on completed round") {
    val a = pid("aaaa")
    val priorTiers = SortedMap[PeerId, Int](a -> Witness)
    val roundStart = Set.empty[PeerId]
    val signers = SortedSet.empty[PeerId]
    val nextTiers = computeNextTiers(priorTiers, roundStart, signers, roundCompleted = true)
    expect.same(SortedMap[PeerId, Int](a -> Witness), nextTiers)
  }

  pureTest("computeNextTiers: large committee, mixed outcomes") {
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
    val signers = SortedSet[PeerId](p1, p2, p6) // p3, p7 fail to sign
    val onCompleted = computeNextTiers(priorTiers, roundStart, signers, roundCompleted = true)
    val expected = SortedMap[PeerId, Int](
      p1 -> Core,
      p2 -> Core,
      p3 -> Tier1, // demoted
      p4 -> Tier1, // unchanged
      p5 -> Witness, // unchanged
      p6 -> Core,
      p7 -> Tier1 // demoted
    )
    expect.same(expected, onCompleted)
  }

  pureTest("computeNextTiers: same large committee, FAILED round, every peer keeps prior tier") {
    val peers = (1 to 5).map(i => pid(f"peer$i%04d"))
    val Seq(p1, p2, p3, p4, p5) = peers
    val priorTiers = SortedMap[PeerId, Int](
      p1 -> Core,
      p2 -> Core,
      p3 -> Tier1,
      p4 -> Witness,
      p5 -> Core
    )
    val roundStart = Set[PeerId](p1, p2, p3, p5) // p4 not in roundStart
    val signers = SortedSet[PeerId](p1) // round failed; only p1 happened to sign
    val onFailed = computeNextTiers(priorTiers, roundStart, signers, roundCompleted = false)
    expect.same(priorTiers, onFailed)
  }
}
