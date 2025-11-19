package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.data.StateT
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.schema.peer.PeerId

import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

case class Previous[A](a: A)

trait ConsensusStateAdvancer[F[_], Key, Artifact, Context, Status, Outcome, Kind] {

  type State = ConsensusState[Key, Status, Outcome, Kind]
  private type Resources = ConsensusResources[Artifact, Kind]

  def getConsensusOutcome(
    state: ConsensusState[Key, Status, Outcome, Kind]
  ): Option[(Previous[Key], Outcome)]

  def advanceStatus(resources: ConsensusResources[Artifact, Kind]): StateT[F, ConsensusState[Key, Status, Outcome, Kind], F[Unit]]

  def logger(implicit async: Async[F]): SelfAwareStructuredLogger[F] =
    Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

  protected def clusterStorage: ClusterStorage[F]

  protected def config: ConsensusConfig

  protected def maybeGetAllDeclarations[A](
    state: State,
    resources: Resources
  )(
    getter: PeerDeclarations => Option[A]
  )(implicit asyncF: Async[F]): F[Option[SortedMap[PeerId, A]]] = {

    val currentFacilitators = state.facilitators.value
    val totalFacilitators = currentFacilitators.size

    val declarations = currentFacilitators.flatMap { peerId =>
      resources.peerDeclarationsMap
        .get(peerId)
        .flatMap(getter)
        .map((peerId, _))
    }

    val declarationsMap = SortedMap.from(declarations)
    val receivedCount = declarationsMap.size

    if (receivedCount == totalFacilitators) {
      declarationsMap.some.pure[F]
    } else {
      none[SortedMap[PeerId, A]].pure[F]
    }
  }
}
