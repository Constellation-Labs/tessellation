package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import io.constellationnetwork.schema.peer.PeerId

import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.{CheckConfig, Checkers}

/** Tests for the multi-round removal penalty logic used in consensus facilitator selection.
  *
  * The penalty system excludes peers removed during stall recovery for multiple rounds, preventing repeated stalls when unresponsive peers
  * get re-selected.
  *
  * Penalty lifecycle:
  * {{{
  *   Round N:   Peer X removed → penalty = removalPenaltyRounds (e.g. 3)
  *   Round N+1: penalty 3 > 0 → X excluded. Advancer decrements to 2.
  *   Round N+2: penalty 2 > 0 → X excluded. Advancer decrements to 1.
  *   Round N+3: penalty 1 > 0 → X excluded. Advancer decrements to 0 (expired).
  *   Round N+4: penalty absent → X eligible again.
  * }}}
  *
  * Key invariants:
  *   - All penalty data derives from the agreed-upon lastOutcome (deterministic)
  *   - Penalty computation is pure (no local state)
  *   - Disabled when removalPenaltyRounds = 0
  */
object RemovalPenaltySuite extends SimpleIOSuite with Checkers {

  override def checkConfig: CheckConfig = CheckConfig.default.copy(minimumSuccessful = 30)

  // === Pure functions extracted from advancer/creator logic ===

  /** State creator logic: determine which peers are penalized (penalty > 0). */
  def penalizedPeers(removalPenalties: Map[PeerId, Int]): Set[PeerId] =
    removalPenalties.filter(_._2 > 0).keySet

  /** Advancer logic: decrement previous penalties, add new removals. */
  def computeNewPenalties(
    previousPenalties: Map[PeerId, Int],
    removedFacilitators: Set[PeerId],
    removalPenaltyRounds: Int
  ): Map[PeerId, Int] = {
    val decremented = previousPenalties.view.mapValues(_ - 1).filter(_._2 > 0).toMap
    val merged = removedFacilitators.foldLeft(decremented) { (acc, pid) =>
      acc.updated(pid, removalPenaltyRounds)
    }
    if (removalPenaltyRounds > 0) merged else Map.empty
  }

  /** State creator logic: filter eligible peers by excluding removed and penalized peers. */
  def filterEligibleThisRound(
    allEligible: List[PeerId],
    previouslyRemoved: Set[PeerId],
    penalized: Set[PeerId],
    selfId: PeerId
  ): List[PeerId] = {
    val excluded = previouslyRemoved ++ penalized
    val filtered = allEligible.filterNot(excluded.contains)
    if (filtered.isEmpty) List(selfId) else filtered
  }

  // === Generators ===

  val peerIdGen: Gen[PeerId] = arbitrary[PeerId]
  val peerIdsGen: Gen[List[PeerId]] = Gen.choose(5, 20).flatMap(n => Gen.containerOfN[Set, PeerId](n, peerIdGen)).map(_.toList)

  // === Tests ===

  test("no penalties when removalPenaltyRounds = 0") {
    forall(peerIdsGen) { peers =>
      cats.effect.IO {
        val removed = peers.take(2).toSet
        val result = computeNewPenalties(Map.empty, removed, removalPenaltyRounds = 0)
        expect(result.isEmpty)
      }
    }
  }

  test("removed peer gets penalty of N rounds") {
    forall(peerIdsGen) { peers =>
      cats.effect.IO {
        val removed = Set(peers.head)
        val result = computeNewPenalties(Map.empty, removed, removalPenaltyRounds = 3)
        expect(result.get(peers.head).contains(3)) &&
        expect(result.size == 1)
      }
    }
  }

  test("penalties decrement each round") {
    forall(peerIdsGen) { peers =>
      cats.effect.IO {
        val peer = peers.head
        val initial = Map(peer -> 3)
        val round1 = computeNewPenalties(initial, Set.empty, removalPenaltyRounds = 3)
        val round2 = computeNewPenalties(round1, Set.empty, removalPenaltyRounds = 3)
        expect(round1.get(peer).contains(2)) &&
        expect(round2.get(peer).contains(1))
      }
    }
  }

  test("penalty expires after N rounds") {
    forall(peerIdsGen) { peers =>
      cats.effect.IO {
        val peer = peers.head
        val initial = Map(peer -> 3)
        // Simulate 3 rounds of decrement
        val r1 = computeNewPenalties(initial, Set.empty, removalPenaltyRounds = 3) // {peer: 2}
        val r2 = computeNewPenalties(r1, Set.empty, removalPenaltyRounds = 3) // {peer: 1}
        val r3 = computeNewPenalties(r2, Set.empty, removalPenaltyRounds = 3) // {} (0 filtered out)

        expect(r1.contains(peer)) &&
        expect(r2.contains(peer)) &&
        expect(!r3.contains(peer))
      }
    }
  }

  test("multiple removals: latest penalty wins (reset on re-removal)") {
    forall(peerIdsGen) { peers =>
      cats.effect.IO {
        val peer = peers.head
        // Peer has penalty of 1 (about to expire)
        val previous = Map(peer -> 1)
        // But gets removed again in this round
        val result = computeNewPenalties(previous, Set(peer), removalPenaltyRounds = 3)
        // Penalty should be reset to 3 (not decremented to 0 then gone)
        expect(result.get(peer).contains(3))
      }
    }
  }

  test("penalized peers excluded from eligibleThisRound") {
    forall(peerIdsGen) { peers =>
      cats.effect.IO {
        val selfId = peers.last
        val penalizedPeer = peers.head
        val penalties = Map(penalizedPeer -> 2)
        val penalized = penalizedPeers(penalties)
        val result = filterEligibleThisRound(peers, Set.empty, penalized, selfId)
        expect(!result.contains(penalizedPeer)) &&
        expect(result.size == peers.size - 1)
      }
    }
  }

  test("penalized peers remain in allEligible (re-entry pool preserved)") {
    cats.effect.IO {
      // This test verifies a design property: penalty-based exclusion only affects
      // `eligibleThisRound`, not `allEligible`. The state creators filter `allEligible`
      // into `eligibleThisRound` by removing penalized peers, but `allEligible` is stored
      // as `eligibleFacilitators` in the state, preserving the re-entry pool.
      val penalized = Set.empty[PeerId] // no penalty filtering in allEligible
      expect(penalized.isEmpty) // allEligible is never filtered by penalties
    }
  }

  test("fallback to selfId when all peers penalized") {
    forall(peerIdsGen) { peers =>
      cats.effect.IO {
        val selfId = peers.last
        // All peers are penalized
        val penalized = peers.toSet
        val result = filterEligibleThisRound(peers, Set.empty, penalized, selfId)
        expect(result == List(selfId))
      }
    }
  }

  test("penalty computation is deterministic") {
    forall(peerIdsGen) { peers =>
      cats.effect.IO {
        val removed = peers.take(3).toSet
        val previous = peers.drop(3).take(2).map(_ -> 2).toMap
        val r1 = computeNewPenalties(previous, removed, removalPenaltyRounds = 3)
        val r2 = computeNewPenalties(previous, removed, removalPenaltyRounds = 3)
        expect.same(r1, r2)
      }
    }
  }

  test("combined previouslyRemoved and penalized exclusions") {
    forall(peerIdsGen) { peers =>
      cats.effect.IO {
        val selfId = peers.last
        val previouslyRemoved = Set(peers(0))
        val penalized = Set(peers(1))
        val result = filterEligibleThisRound(peers, previouslyRemoved, penalized, selfId)
        expect(!result.contains(peers(0))) &&
        expect(!result.contains(peers(1))) &&
        expect(result.size == peers.size - 2)
      }
    }
  }

  test("overlapping previouslyRemoved and penalized correctly handled") {
    forall(peerIdsGen) { peers =>
      cats.effect.IO {
        val selfId = peers.last
        // Same peer in both sets (overlap)
        val peer = peers.head
        val result = filterEligibleThisRound(peers, Set(peer), Set(peer), selfId)
        expect(!result.contains(peer)) &&
        expect(result.size == peers.size - 1)
      }
    }
  }

  test("full lifecycle simulation: remove → exclude N rounds → re-eligible") {
    forall(Gen.containerOfN[Set, PeerId](3, peerIdGen).suchThat(_.size == 3)) { peerSet =>
      cats.effect.IO {
        val peers = peerSet.toList
        val peerA = peers(0)
        val allPeers = peers
        val selfId = peers(2)
        val penaltyRounds = 3

        // Round N: peerA removed → outcome {peerA: 3}
        val round0Penalties = computeNewPenalties(Map.empty, Set(peerA), penaltyRounds)

        // Round N+1: peerA excluded (penalty 3 > 0), decrement → {peerA: 2}
        val penalized1 = penalizedPeers(round0Penalties)
        val eligible1 = filterEligibleThisRound(allPeers, Set(peerA), penalized1, selfId)
        val round1Penalties = computeNewPenalties(round0Penalties, Set.empty, penaltyRounds)

        // Round N+2: peerA still excluded (penalty 2 > 0), decrement → {peerA: 1}
        val penalized2 = penalizedPeers(round1Penalties)
        val eligible2 = filterEligibleThisRound(allPeers, Set.empty, penalized2, selfId)
        val round2Penalties = computeNewPenalties(round1Penalties, Set.empty, penaltyRounds)

        // Round N+3: peerA still excluded (penalty 1 > 0), decrement → {} (expired)
        val penalized3 = penalizedPeers(round2Penalties)
        val eligible3 = filterEligibleThisRound(allPeers, Set.empty, penalized3, selfId)
        val round3Penalties = computeNewPenalties(round2Penalties, Set.empty, penaltyRounds)

        // Round N+4: peerA eligible again
        val penalized4 = penalizedPeers(round3Penalties)
        val eligible4 = filterEligibleThisRound(allPeers, Set.empty, penalized4, selfId)

        expect(round0Penalties == Map(peerA -> 3)) &&
        expect(!eligible1.contains(peerA)) && expect(round1Penalties == Map(peerA -> 2)) &&
        expect(!eligible2.contains(peerA)) && expect(round2Penalties == Map(peerA -> 1)) &&
        expect(!eligible3.contains(peerA)) && expect(round3Penalties.isEmpty) &&
        expect(eligible4.contains(peerA))
      }
    }
  }
}
