package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.data.StateT
import cats.effect.{Async, Clock}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.schema.peer.PeerId

import org.typelevel.log4cats.Logger

case class Previous[A](a: A)

trait ConsensusStateAdvancer[F[_], Key, Artifact, Context, Status, Outcome, Kind] {

  type State = ConsensusState[Key, Status, Outcome, Kind]
  type Resources = ConsensusResources[Artifact, Kind]

  def getConsensusOutcome(
    state: ConsensusState[Key, Status, Outcome, Kind]
  ): Option[(Previous[Key], Outcome)]

  def advanceStatus(resources: ConsensusResources[Artifact, Kind]): StateT[F, ConsensusState[Key, Status, Outcome, Kind], F[Unit]]

  protected def maybeGetAllDeclarations[A](state: State, resources: Resources, config: ConsensusConfig)(
    getter: PeerDeclarations => Option[A]
  )(implicit F: Async[F], logger: Logger[F]): F[Option[SortedMap[PeerId, A]]] = {
    val processNonStale = state.facilitators.value.traverse { peerId =>
      resources.peerDeclarationsMap
        .get(peerId)
        .flatMap(getter)
        .map((peerId, _))
    }.map(SortedMap.from(_))

    val processStale = state.facilitators.value.toList.traverse { peerId =>
      resources.peerDeclarationsMap
        .get(peerId)
        .flatMap(getter)
        .map((peerId, _))
    }.map(_.toMap).map(SortedMap.from(_))

    for {
      now <- Clock[F].monotonic
      elapsed = now - resources.updatedAt
      isStale = elapsed > config.peersDeclarationTimeout
      result <-
        if (isStale) {
          logger.warn(
            s"The process is stale when getting all declarations. Elapsed: ${elapsed.toSeconds}s, Timeout: ${config.peersDeclarationTimeout.toSeconds}s"
          ) >> F.pure(processStale)
        } else {
          F.pure(processNonStale)
        }
    } yield result
  }
}
