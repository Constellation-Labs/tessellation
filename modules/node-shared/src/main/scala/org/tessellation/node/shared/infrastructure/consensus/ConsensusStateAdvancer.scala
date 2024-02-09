package org.tessellation.node.shared.infrastructure.consensus

import cats.data.StateT
import cats.syntax.traverse._

import scala.collection.immutable.SortedMap

import org.tessellation.schema.peer.PeerId

case class Previous[A](a: A)

trait ConsensusStateAdvancer[F[_], Key, Artifact, Context, Status, Outcome, Kind] {

  type State = ConsensusState[Key, Status, Outcome, Kind]
  type Resources = ConsensusResources[Artifact, Kind]

  def getConsensusOutcome(
    state: ConsensusState[Key, Status, Outcome, Kind]
  ): Option[(Previous[Key], Outcome)]

  def advanceStatus(resources: ConsensusResources[Artifact, Kind]): StateT[F, ConsensusState[Key, Status, Outcome, Kind], F[Unit]]

  protected def maybeGetAllDeclarations[A](state: State, resources: Resources)(
    getter: PeerDeclarations => Option[A]
  ): Option[SortedMap[PeerId, A]] =
    state.facilitators.value.traverse { peerId =>
      resources.peerDeclarationsMap
        .get(peerId)
        .flatMap(getter)
        .map((peerId, _))
    }.map(SortedMap.from(_))
}
