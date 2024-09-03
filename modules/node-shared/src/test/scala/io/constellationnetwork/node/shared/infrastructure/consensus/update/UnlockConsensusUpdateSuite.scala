package io.constellationnetwork.node.shared.infrastructure.consensus.update

import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.node.shared.infrastructure.consensus._
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

  test("state either transitions to target state or remains in initial state, regardless of what subset of acks is processed") {
    forall(lockedStateAndResourcesGen) {
      case (initialState, resources) =>
        unlockConsensusFn(resources).run(initialState).flatMap {
          case (targetState, _) =>
            val partialResourcesGen =
              Gen.someOf(resources.acksMap).map(_.toMap).map(partialAcksMap => resources.copy(acksMap = partialAcksMap))

            forall(partialResourcesGen) { partialResources =>
              unlockConsensusFn(partialResources).run(initialState).map {
                case (state, _) =>
                  expect.same(initialState, state).xor(expect.same(targetState, state))
              }
            }
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
        artifacts = Map.empty[Hash, Artifact]
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
}
