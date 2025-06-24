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

    def processNonStale =
      state.facilitators.value.traverse { peerId =>
        resources.peerDeclarationsMap
          .get(peerId)
          .flatMap(getter)
          .map((peerId, _))
      }.map(SortedMap.from(_))

    def processStale = {
      val results = state.facilitators.value.flatMap { peerId =>
        resources.peerDeclarationsMap
          .get(peerId)
          .flatMap(getter)
          .map((peerId, _))
      }

      if (results.nonEmpty) Some(SortedMap.from(results))
      else None
    }

    for {
      now <- Clock[F].monotonic
      elapsed = now - resources.updatedAt
      isStale = elapsed > config.peersDeclarationTimeout
      result <-
        if (isStale) {
          logger.warn(
            s"The process is stale when getting all declarations. Elapsed: ${elapsed.toSeconds}s, Timeout: ${config.peersDeclarationTimeout.toSeconds}s"
          ) >> processStale.pure
        } else {
          processNonStale.pure
        }
    } yield result
  }
}
