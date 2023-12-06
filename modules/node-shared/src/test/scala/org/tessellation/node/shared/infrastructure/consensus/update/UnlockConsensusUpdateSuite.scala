package org.tessellation.node.shared.infrastructure.consensus.update

import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

import org.tessellation.node.shared.infrastructure.consensus._
import org.tessellation.node.shared.infrastructure.consensus.declaration.kind
import org.tessellation.node.shared.infrastructure.consensus.declaration.kind.PeerDeclarationKind
import org.tessellation.node.shared.infrastructure.consensus.trigger.TimeTrigger
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.{CheckConfig, Checkers}

object UnlockConsensusUpdateSuite extends SimpleIOSuite with Checkers {

  type Key = Int
  type Artifact = Unit
  type Context = Unit

  val unlockConsensusFn: ConsensusStateUpdateFn[UnlockConsensusUpdateSuite.F, Key, Artifact, Artifact, Context] =
    UnlockConsensusUpdate.make[F, Key, Artifact, Context]

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
            expect(state.lockStatus === Reopened) &&
            expect(state.removedFacilitators.union(state.facilitators.toSet) === initialState.facilitators.toSet) &&
            expect(state.removedFacilitators.intersect(state.facilitators.toSet) === Set.empty)
        }
    }
  }

  def lockedStateAndResourcesGen: Gen[(ConsensusState[Key, Artifact, Context], ConsensusResources[Artifact])] =
    for {
      facilitators <- facilitatorsGen
      state <- lockedStateGen(facilitators)
      acksMap <- acksMapGen(facilitators)
      resources <- resourcesGen(acksMap)
    } yield (state, resources)

  def facilitatorsGen: Gen[List[PeerId]] =
    Gen
      .choose(10, 100)
      .flatMap(size => Gen.containerOfN[Set, PeerId](size, arbitrary[PeerId]))
      .map(_.toList.sorted)

  def lockedStateGen(facilitators: List[PeerId]): Gen[ConsensusState[Key, Artifact, Context]] =
    for {
      key <- arbitrary[Key]
      lastKey <- arbitrary[Key]
      createdAt <- arbitrary[FiniteDuration]
      lastSignedArtifact <- arbitrary[Signed[Artifact]]
      lastContext <- arbitrary[Context]
      facilitatorsHash <- arbitrary[Hash]
    } yield
      ConsensusState(
        key = key,
        lastOutcome = ConsensusOutcome(
          lastKey,
          facilitators,
          Set.empty,
          Set.empty,
          Finished(lastSignedArtifact, lastContext, TimeTrigger, facilitators.toSet, facilitatorsHash)
        ),
        facilitators = facilitators,
        status = CollectingFacilities(none, facilitatorsHash),
        createdAt = createdAt,
        lockStatus = Closed
      )

  def acksMapGen(facilitators: List[PeerId]): Gen[Map[(PeerId, PeerDeclarationKind), Set[PeerId]]] =
    Gen.listOfN(facilitators.size, Gen.someOf(facilitators).map(_.toSet)).map { acksSet =>
      facilitators.map(peerId => (peerId, kind.Facility)).zip(acksSet).toMap
    }

  def resourcesGen(acksMap: Map[(PeerId, PeerDeclarationKind), Set[PeerId]]): Gen[ConsensusResources[Artifact]] =
    for {
      peerDeclarations <- arbitrary[Map[PeerId, PeerDeclarations]]
      artifacts <- arbitrary[Map[Hash, Artifact]]
      withdrawals <- arbitrary[Map[PeerId, PeerDeclarationKind]]
    } yield
      ConsensusResources(
        peerDeclarationsMap = peerDeclarations,
        acksMap = acksMap,
        withdrawalsMap = withdrawals,
        ackKinds = Set(kind.Facility),
        artifacts = artifacts
      )

}
