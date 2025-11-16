package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.data.StateT
import cats.effect.{Async, Clock}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.schema.peer.{PeerId, Responsive, Unresponsive}

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

  def logger(implicit async: Async[F]): SelfAwareStructuredLogger[F] =
    Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

  // Abstract method that implementations must provide to access ClusterStorage
  protected def clusterStorage: ClusterStorage[F]

  // Abstract method for config
  protected def config: ConsensusConfig

  private def shouldTimeout(
    state: State,
    resources: Resources,
    now: FiniteDuration
  ): Boolean = {
    val elapsedSinceStateCreated = now - state.createdAt
    val elapsedSinceLastUpdate = now - resources.updatedAt

    elapsedSinceStateCreated > config.peersDeclarationTimeout &&
    elapsedSinceLastUpdate > config.peersDeclarationTimeout
  }

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
    val missingPeers = currentFacilitators.filterNot(declarationsMap.contains)

    for {
      now <- Clock[F].monotonic
      _ <- markSlowPeersAsUnresponsive(missingPeers, state, resources, now)

      result <-
        if (receivedCount == totalFacilitators) {
          // All facilitators responded
          declarationsMap.some.pure[F]
        } else {
          // Still waiting
          none[SortedMap[PeerId, A]].pure[F]
        }
    } yield result
  }

  /** Get partial declarations after timeout. This is used when we must proceed despite missing peers.
    */
  protected def getPartialDeclarations[A](
    state: State,
    resources: Resources,
    elapsed: FiniteDuration,
    phaseName: String
  )(
    getter: PeerDeclarations => Option[A]
  )(implicit asyncF: Async[F]): F[Option[SortedMap[PeerId, A]]] = {

    val declarations = state.facilitators.value.flatMap { peerId =>
      resources.peerDeclarationsMap
        .get(peerId)
        .flatMap(getter)
        .map((peerId, _))
    }

    val declarationsMap = SortedMap.from(declarations)
    val missingPeers = state.facilitators.value.filterNot(declarationsMap.contains)

    logger.error(
      s"TIMEOUT at $phaseName phase (${elapsed.toSeconds}s): Proceeding with ${declarationsMap.size}/${state.facilitators.value.size} facilitators. " +
        s"Missing: ${missingPeers.map(_.show).take(3).mkString(", ")}${if (missingPeers.size > 3) "..." else ""}"
    ) >>
      // Mark missing peers as unresponsive
      missingPeers.traverse_(peerId => clusterStorage.setPeerResponsiveness(peerId, Unresponsive)) >>
      declarationsMap.some.pure[F]
  }

  private def markSlowPeersAsUnresponsive(
    missingPeers: List[PeerId],
    state: State,
    resources: Resources,
    now: FiniteDuration
  )(implicit asyncF: Async[F]): F[Unit] =
    if (shouldTimeout(state, resources, now) && missingPeers.nonEmpty) {
      missingPeers.traverse_ { peerId =>
        clusterStorage.getPeer(peerId).flatMap {
          case Some(peer) if peer.responsiveness === Responsive =>
            logger.warn(
              s"Marking peer ${peerId.show} as Unresponsive - no declaration"
            ) >> clusterStorage.setPeerResponsiveness(peerId, Unresponsive)
          case _ =>
            Async[F].unit
        }
      }
    } else {
      Async[F].unit
    }

  /** Update consensus state to remove unresponsive facilitators. Returns updated state and the set of removed peers.
    */
  protected def removeMissingFacilitators(
    state: State,
    respondedPeers: SortedSet[PeerId],
    phaseName: String
  )(implicit asyncF: Async[F]): F[(State, List[PeerId])] = {
    val missingPeers = state.facilitators.value.filterNot(respondedPeers.contains)

    if (missingPeers.isEmpty) {
      (state, List.empty[PeerId]).pure[F]
    } else {
      logger
        .warn(
          s"Removing ${missingPeers.size} unresponsive facilitators at $phaseName phase: " +
            s"${missingPeers.map(_.show).take(3).mkString(", ")}"
        )
        .as {
          val updatedState = state.copy(
            facilitators = Facilitators(state.facilitators.value.filterNot(missingPeers.contains)),
            removedFacilitators = RemovedFacilitators(state.removedFacilitators.value ++ missingPeers)
          )
          (updatedState, missingPeers)
        }
    }
  }
}
