package io.constellationnetwork.node.shared.infrastructure.consensus.update

import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.{CheckConfig, Checkers}

object UnlockConsensusUpdateSuite extends SimpleIOSuite with Checkers {

  type Key = Int
  type Artifact = Unit
  type Context = Unit
  type Status = Either[Unit, Unit]
  type Outcome = Unit
  type Kind = Unit

  val unlockConsensusFn: ConsensusStateUpdateFn[UnlockConsensusUpdateSuite.F, Key, Artifact, Status, Outcome, Kind, Unit] =
    (resources: ConsensusResources[Artifact, Kind]) =>
      UnlockConsensusUpdate.tryUnlock[F, ConsensusState[Key, Status, Outcome, Kind], Kind](resources.acksMap)(
        _.status match {
          case Left(_)  => ().some
          case Right(_) => none
        }
      )

  override def checkConfig: CheckConfig = CheckConfig.default.copy(minimumSuccessful = 40)

  test("partial ACKs produce a valid unlock or leave state unchanged") {
    forall(lockedStateAndResourcesGen) {
      case (initialState, resources) =>
        val partialResourcesGen =
          Gen.someOf(resources.acksMap).map(_.toMap).map(partialAcksMap => resources.copy(acksMap = partialAcksMap))

        forall(partialResourcesGen) { partialResources =>
          unlockConsensusFn(partialResources).run(initialState).map {
            case (state, _) =>
              if (state.lockStatus === LockStatus.Closed) {
                // No unlock happened — state should be exactly the initial state
                expect.same(initialState, state)
              } else {
                // Unlock happened — verify structural invariants:
                // 1. Lock status transitioned to Reopened
                // 2. Kept + removed = original facilitators (no peers lost or invented)
                // 3. Kept and removed are disjoint
                expect(state.lockStatus === LockStatus.Reopened) &&
                expect(
                  state.removedFacilitators.value.union(state.facilitators.value.toSet) === initialState.facilitators.value.toSet
                ) &&
                expect(state.removedFacilitators.value.intersect(state.facilitators.value.toSet) === Set.empty)
              }
          }
        }
    }
  }

  test("unlock is deterministic - same inputs produce same result") {
    forall(lockedStateAndResourcesGen) {
      case (initialState, resources) =>
        val partialResourcesGen =
          Gen.someOf(resources.acksMap).map(_.toMap).map(partialAcksMap => resources.copy(acksMap = partialAcksMap))

        forall(partialResourcesGen) { partialResources =>
          for {
            (state1, _) <- unlockConsensusFn(partialResources).run(initialState)
            (state2, _) <- unlockConsensusFn(partialResources).run(initialState)
          } yield expect.same(state1, state2)
        }
    }
  }

  test("state transitions to reopened and removed facilitators are disjoint with facilitators") {
    forall(lockedStateAndResourcesGen) {
      case (initialState, resources) =>
        unlockConsensusFn(resources).run(initialState).map {
          case (state, _) =>
            expect(state.lockStatus === LockStatus.Reopened) &&
            expect(state.removedFacilitators.value.union(state.facilitators.value.toSet) === initialState.facilitators.value.toSet) &&
            expect(state.removedFacilitators.value.intersect(state.facilitators.value.toSet) === Set.empty)
        }
    }
  }

  def lockedStateAndResourcesGen: Gen[(ConsensusState[Key, Status, Outcome, Kind], ConsensusResources[Artifact, Kind])] =
    for {
      facilitators <- facilitatorsGen
      state <- lockedStateGen(facilitators)
      acksMap <- acksMapGen(facilitators)
      resources = ConsensusResources(
        peerDeclarationsMap = Map.empty,
        acksMap = acksMap,
        withdrawalsMap = Map.empty,
        ackKinds = Set.empty,
        artifacts = Map.empty[Hash, Artifact],
        updatedAt = FiniteDuration(10, "seconds")
      )
    } yield (state, resources)

  def facilitatorsGen: Gen[List[PeerId]] =
    Gen
      .choose(10, 100)
      .flatMap(size => Gen.containerOfN[Set, PeerId](size, arbitrary[PeerId]))
      .map(_.toList.sorted)

  def lockedStateGen(facilitators: List[PeerId]): Gen[ConsensusState[Key, Status, Outcome, Kind]] =
    for {
      key <- arbitrary[Key]
      createdAt <- arbitrary[FiniteDuration]
      facilitatorsHash <- arbitrary[Hash]
    } yield
      ConsensusState(
        key = key,
        lastOutcome = (),
        facilitators = Facilitators(facilitators),
        status = ().asLeft,
        createdAt = createdAt,
        lockStatus = LockStatus.Closed,
        spreadAckKinds = Set.empty
      )

  def acksMapGen(facilitators: List[PeerId]): Gen[Map[(PeerId, Kind), Set[PeerId]]] =
    Gen.listOfN(facilitators.size, Gen.someOf(facilitators).map(_.toSet)).map { acksSet =>
      facilitators.map(peerId => (peerId, ())).zip(acksSet).toMap
    }

  // === Adaptive tiered threshold tests ===

  /** Simulates threshold tier selection logic from UnlockConsensusUpdate */
  def computeThresholds(n: Int, voterCount: Int): Option[(Int, Int)] = {
    val degradedQuorum = math.ceil(n.toDouble / 5).toInt.max(2)
    val standardQuorum = math.ceil(n.toDouble / 3).toInt.max(2)
    val normalKeepThreshold = (n + 1) / 2

    val superThreshold = math.ceil(voterCount.toDouble * 2 / 3).toInt.max(1)
    if (voterCount < degradedQuorum) none
    else if (voterCount < standardQuorum) (superThreshold, superThreshold).some
    else if (voterCount < normalKeepThreshold) ((voterCount + 1) / 2, voterCount / 2 + 1).some
    else (normalKeepThreshold, n / 2 + 1).some
  }

  test("DEFER: no thresholds when voterCount < degradedQuorum") {
    cats.effect.IO {
      val n = 20
      val voterCount = 3
      val result = computeThresholds(n, voterCount)
      // degradedQuorum = ceil(20/5) = 4, voterCount = 3 < 4 → DEFER
      expect(result.isEmpty)
    }
  }

  test("DEGRADED: 2/3 supermajority of voters required") {
    cats.effect.IO {
      val n = 20
      val voterCount = 5
      val result = computeThresholds(n, voterCount)
      // degradedQuorum = 4, standardQuorum = 7, 4 <= 5 < 7 → DEGRADED
      // superThreshold = ceil(5 * 2/3) = ceil(3.33) = 4
      expect(result.isDefined) &&
      expect.same((4, 4), result.get)
    }
  }

  test("FALLBACK: simple majority of voters suffices") {
    cats.effect.IO {
      val n = 20
      val voterCount = 8
      val result = computeThresholds(n, voterCount)
      // standardQuorum = 7, normalKeepThreshold = 10, 7 <= 8 < 10 → FALLBACK
      // keepThreshold = (8+1)/2 = 4, removeThreshold = 8/2 + 1 = 5
      expect(result.isDefined) &&
      expect.same((4, 5), result.get)
    }
  }

  test("NORMAL: standard thresholds based on total facilitator count") {
    cats.effect.IO {
      val n = 20
      val voterCount = 15
      val result = computeThresholds(n, voterCount)
      // normalKeepThreshold = 10, 15 >= 10 → NORMAL
      // keepThreshold = 10, removeThreshold = 11
      expect(result.isDefined) &&
      expect.same((10, 11), result.get)
    }
  }

  test("tier transitions are deterministic for same inputs") {
    forall(Gen.choose(5, 100)) { n =>
      forall(Gen.choose(0, n)) { voterCount =>
        cats.effect.IO {
          val t1 = computeThresholds(n, voterCount)
          val t2 = computeThresholds(n, voterCount)
          expect.same(t1, t2)
        }
      }
    }
  }

  test("DEFER prevents unlock when too few facilitators voted") {
    forall(facilitatorsGen) { facilitators =>
      val n = facilitators.size
      val degradedQuorum = math.ceil(n.toDouble / 5).toInt.max(2)
      val tooFewVoters = math.max(1, degradedQuorum - 1)

      val voters = facilitators.take(tooFewVoters)
      val acksMap: Map[(PeerId, Kind), Set[PeerId]] =
        voters.map(peerId => ((peerId, ()), facilitators.toSet)).toMap

      val stateGen = lockedStateGen(facilitators)
      forall(stateGen) { state =>
        val resources = ConsensusResources(
          peerDeclarationsMap = Map.empty,
          acksMap = acksMap,
          withdrawalsMap = Map.empty,
          ackKinds = Set.empty,
          artifacts = Map.empty[Hash, Artifact],
          updatedAt = FiniteDuration(10, "seconds")
        )

        unlockConsensusFn(resources).run(state).map {
          case (resultState, _) =>
            expect(resultState.lockStatus === LockStatus.Closed)
        }
      }
    }
  }
}
