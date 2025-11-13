package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.data.StateT
import cats.effect.{Async, Clock}
import cats.syntax.all._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.DurationInt

import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.schema.peer.PeerId

import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

case class Previous[A](a: A)

trait ConsensusStateAdvancer[F[_], Key, Artifact, Context, Status, Outcome, Kind] {

  type State = ConsensusState[Key, Status, Outcome, Kind]
  type Resources = ConsensusResources[Artifact, Kind]

  def getConsensusOutcome(
    state: ConsensusState[Key, Status, Outcome, Kind]
  ): Option[(Previous[Key], Outcome)]

  def advanceStatus(resources: ConsensusResources[Artifact, Kind]): StateT[F, ConsensusState[Key, Status, Outcome, Kind], F[Unit]]

  def logger(implicit async: Async[F]): SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F]("ConsensusStateAdvancer")

  protected def maybeGetAllDeclarations[A](
    state: State,
    resources: Resources,
    config: ConsensusConfig
  )(
    getter: PeerDeclarations => Option[A]
  )(implicit asyncF: Async[F]): F[Option[SortedMap[PeerId, A]]] = {

    val totalFacilitators = state.facilitators.value.size

    val declarations = state.facilitators.value.flatMap { peerId =>
      resources.peerDeclarationsMap
        .get(peerId)
        .flatMap(getter)
        .map((peerId, _))
    }

    val declarationsMap = SortedMap.from(declarations)
    val receivedCount = declarationsMap.size

    // CRITICAL: Must have ALL facilitators for deterministic consensus
    if (receivedCount == totalFacilitators) {
      declarationsMap.some.pure[F]
    } else {
      none[SortedMap[PeerId, A]].pure[F]
    }
  }
}