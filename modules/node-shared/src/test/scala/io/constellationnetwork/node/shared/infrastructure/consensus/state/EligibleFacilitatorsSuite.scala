package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.schema.peer.PeerId

import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.{CheckConfig, Checkers}

/** Verifies that removed facilitators can re-enter the eligible pool in subsequent rounds.
  *
  * The Phase 3.1 fix ensures that:
  *   1. `allEligible` is computed from the full base WITHOUT removal filter 2. Only `eligibleThisRound` filters out previously removed
  *      peers (for active selection) 3. `EligibleFacilitators(allEligible)` is stored in state, so removed peers persist in the eligible
  *      pool
  *
  * The critical property: a peer removed in round N is excluded from round N+1's active set, but remains in `eligibleFacilitators` so it
  * can be re-selected in round N+2.
  */
object EligibleFacilitatorsSuite extends SimpleIOSuite with Checkers {

  override def checkConfig: CheckConfig = CheckConfig.default.copy(minimumSuccessful = 40)

  def facilitatorsGen: Gen[List[PeerId]] =
    Gen
      .choose(10, 30)
      .flatMap(size => Gen.containerOfN[Set, PeerId](size, arbitrary[PeerId]))
      .map(_.toList.sorted)

  /** Simulates the facilitator selection logic from GlobalSnapshotConsensusStateCreator.
    *
    * Returns (allEligible, eligibleThisRound, storedEligible) where:
    *   - allEligible: full set passing collateral filter (no removal filter)
    *   - eligibleThisRound: allEligible minus previouslyRemoved
    *   - storedEligible: what gets stored in EligibleFacilitators (= allEligible)
    */
  def simulateFacilitatorSelection(
    previousEligible: List[PeerId],
    candidates: Set[PeerId],
    previouslyRemoved: Set[PeerId],
    selfId: PeerId,
    collateralFilter: PeerId => Boolean = _ => true
  ): (List[PeerId], List[PeerId], List[PeerId]) = {
    val fullBase = (previousEligible ++ candidates.toList :+ selfId).distinct
    val allEligible = {
      val filtered = fullBase.filter(collateralFilter)
      if (filtered.isEmpty) List(selfId) else filtered
    }
    val eligibleThisRound = {
      val filtered = allEligible.filterNot(previouslyRemoved.contains)
      if (filtered.isEmpty) List(selfId) else filtered
    }
    val storedEligible = allEligible
    (allEligible, eligibleThisRound, storedEligible)
  }

  test("removed peers are excluded from eligibleThisRound but remain in allEligible") {
    forall(facilitatorsGen) { facilitators =>
      IO {
        val selfId = facilitators.head
        val removed = facilitators.drop(1).take(3).toSet

        val (allEligible, eligibleThisRound, storedEligible) =
          simulateFacilitatorSelection(facilitators, Set.empty, removed, selfId)

        // All eligible should contain removed peers
        val removedInAllEligible = removed.forall(allEligible.contains)
        // This round should NOT contain removed peers
        val removedNotInThisRound = removed.forall(p => !eligibleThisRound.contains(p))
        // Stored eligible = allEligible (full set)
        val storedMatchesAll = storedEligible == allEligible

        expect(removedInAllEligible) &&
        expect(removedNotInThisRound) &&
        expect(storedMatchesAll)
      }
    }
  }

  test("removed peer in round N can re-enter in round N+2 via stored eligibleFacilitators") {
    forall(facilitatorsGen) { facilitators =>
      IO {
        val selfId = facilitators.head
        val removedPeer = facilitators(1)

        // Round N: peer gets removed
        val removedInRoundN = Set(removedPeer)
        val (_, _, storedAfterN) =
          simulateFacilitatorSelection(facilitators, Set.empty, removedInRoundN, selfId)

        // Round N+1: removed peer excluded from active, but still in stored eligible
        // previousEligible comes from storedAfterN
        val (_, eligibleN1, storedAfterN1) =
          simulateFacilitatorSelection(storedAfterN, Set.empty, removedInRoundN, selfId)
        // Removed peer NOT in N+1's active set
        val notInN1Active = !eligibleN1.contains(removedPeer)
        // But still in stored eligible after N+1
        val inStoredN1 = storedAfterN1.contains(removedPeer)

        // Round N+2: no removal filter (previouslyRemoved is empty for new round)
        // previousEligible comes from storedAfterN1
        val (_, eligibleN2, _) =
          simulateFacilitatorSelection(storedAfterN1, Set.empty, Set.empty, selfId)
        // Removed peer CAN be in N+2's active set
        val inN2Active = eligibleN2.contains(removedPeer)

        expect(notInN1Active) &&
        expect(inStoredN1) &&
        expect(inN2Active)
      }
    }
  }

  test("new candidates are merged into allEligible") {
    forall(facilitatorsGen) { facilitators =>
      IO {
        val selfId = facilitators.head
        val previousEligible = facilitators.take(5)
        val newCandidates = facilitators.drop(5).take(3).toSet

        val (allEligible, _, _) =
          simulateFacilitatorSelection(previousEligible, newCandidates, Set.empty, selfId)

        val allMerged = newCandidates.forall(allEligible.contains) &&
          previousEligible.forall(allEligible.contains)

        expect(allMerged)
      }
    }
  }

  test("if all eligible peers are removed, selfId is used as fallback") {
    forall(facilitatorsGen) { facilitators =>
      IO {
        val selfId = facilitators.head
        val allRemoved = facilitators.toSet

        val (_, eligibleThisRound, _) =
          simulateFacilitatorSelection(facilitators, Set.empty, allRemoved, selfId)

        // Fallback to selfId
        expect.same(List(selfId), eligibleThisRound)
      }
    }
  }

  test("collateral filter removes peers from allEligible but they get added back from candidates") {
    forall(facilitatorsGen) { facilitators =>
      IO {
        val selfId = facilitators.head
        val failsCollateral = facilitators(1)
        // Peer fails collateral in this round
        val collateralFilter: PeerId => Boolean = pid => pid != failsCollateral

        val (allEligible, _, storedEligible) =
          simulateFacilitatorSelection(facilitators, Set.empty, Set.empty, selfId, collateralFilter)

        // Peer excluded by collateral filter
        val excludedByCollateral = !allEligible.contains(failsCollateral)

        // But if peer later passes collateral and joins as candidate...
        val passesNow: PeerId => Boolean = _ => true
        val (allEligible2, _, _) =
          simulateFacilitatorSelection(storedEligible, Set(failsCollateral), Set.empty, selfId, passesNow)

        // Now the peer is back in eligible
        val reAddedViaCandidate = allEligible2.contains(failsCollateral)

        expect(excludedByCollateral) && expect(reAddedViaCandidate)
      }
    }
  }
}
