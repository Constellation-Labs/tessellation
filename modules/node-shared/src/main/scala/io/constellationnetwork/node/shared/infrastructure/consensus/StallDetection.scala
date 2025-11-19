package io.constellationnetwork.node.shared.infrastructure.consensus

import cats._
import cats.effect._
import cats.effect.std.Supervisor
import cats.syntax.all._

import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.schema.peer.{PeerId, Unresponsive}

import org.typelevel.log4cats.Logger

private[consensus] object StallDetection {

  def scheduleStallDetection[
    F[_]: Temporal,
    Key,
    Artifact,
    Context,
    Status,
    OutcomeC,
    Kind
  ](
    key: Key,
    state: ConsensusState[Key, Status, OutcomeC, Kind],
    config: ConsensusConfig,
    stallDetectionRef: Ref[F, Map[Key, Long]],
    consensusOps: ConsensusOps[Status, Kind],
    consensusStorage: ConsensusStorage[F, _, Key, Artifact, Context, Status, OutcomeC, Kind],
    consensusStateUpdater: ConsensusStateUpdater[F, Key, Artifact, Context, Status, OutcomeC, Kind],
    clusterStorage: ClusterStorage[F],
    queue: ConsensusQueue[F, Key],
    logger: Logger[F]
  )(implicit S: Supervisor[F]): F[Unit] =
    Clock[F].monotonic.flatMap { now =>
      val stallId = now.toMillis

      stallDetectionRef.update(_ + (key -> stallId)) >>
        S.supervise {
          Temporal[F].sleep(config.declarationTimeout) >>
            stallDetectionRef.get.flatMap { currentMap =>
              if (currentMap.get(key).contains(stallId)) {
                logger.warn(s"Stall detected for consensus round {key=${key.toString}}") >>
                  processStallDetection(
                    key,
                    state,
                    config,
                    consensusOps,
                    consensusStorage,
                    consensusStateUpdater,
                    clusterStorage,
                    queue,
                    logger
                  ) >>
                  stallDetectionRef.update(_ - key)
              } else {
                Applicative[F].unit
              }
            }.handleErrorWith { err =>
              logger.error(err)(s"Error in stall detection {key=${key.toString}}") >>
                stallDetectionRef.update(_ - key)
            }
        }.void
    }

  private[consensus] def processStallDetection[
    F[_]: Temporal,
    Key,
    Artifact,
    Context,
    Status,
    OutcomeC,
    Kind
  ](
    key: Key,
    state: ConsensusState[Key, Status, OutcomeC, Kind],
    config: ConsensusConfig,
    consensusOps: ConsensusOps[Status, Kind],
    consensusStorage: ConsensusStorage[F, _, Key, Artifact, Context, Status, OutcomeC, Kind],
    consensusStateUpdater: ConsensusStateUpdater[F, Key, Artifact, Context, Status, OutcomeC, Kind],
    clusterStorage: ClusterStorage[F],
    queue: ConsensusQueue[F, Key],
    logger: Logger[F]
  ): F[Unit] =
    consensusStateUpdater
      .tryLockConsensus(key, state)
      .flatMap {
        case Some((_, lockedState)) =>
          logger.info(s"Consensus locked for stall recovery {key=${key.toString}}") >>
            Temporal[F].sleep(config.lockDuration) >>
            consensusOps
              .maybeCollectingKind(lockedState.status)
              .traverse { ackKind =>
                for {
                  resources <- consensusStorage.getResources(key)
                  _ <- consensusStateUpdater.trySpreadAck(key, ackKind, resources)
                  _ <- logger.debug(s"ACKs spread for stall recovery {key=${key.toString}, kind=${ackKind.toString}}")
                  _ <- removeUnresponsivePeersAndRetry(
                    key,
                    lockedState,
                    ackKind,
                    resources,
                    consensusStorage,
                    clusterStorage,
                    queue,
                    consensusOps,
                    logger
                  )
                } yield ()
              }
              .void

        case None =>
          logger.debug(s"Could not lock consensus for stall recovery {key=${key.toString}}")
      }

  private def removeUnresponsivePeersAndRetry[
    F[_]: Monad,
    Key,
    Artifact,
    Context,
    Status,
    OutcomeC,
    Kind
  ](
    key: Key,
    state: ConsensusState[Key, Status, OutcomeC, Kind],
    ackKind: Kind,
    resources: ConsensusResources[Artifact, Kind],
    consensusStorage: ConsensusStorage[F, _, Key, Artifact, Context, Status, OutcomeC, Kind],
    clusterStorage: ClusterStorage[F],
    queue: ConsensusQueue[F, Key],
    consensusOps: ConsensusOps[Status, Kind],
    logger: Logger[F]
  ): F[Unit] = {

    val responded = getRespondedPeers(resources, ackKind, consensusOps)
    val missing = state.facilitators.value.filterNot(responded.contains)

    if (missing.nonEmpty) {
      logger.warn(
        s"Stall recovery: Removing ${missing.size} unresponsive facilitators {key=${key.toString}}: " +
          s"${missing.map(_.toString).take(3).mkString(", ")}${if (missing.size > 3) "..." else ""}"
      ) >>
        missing.traverse_(peerId => clusterStorage.setPeerResponsiveness(peerId, Unresponsive)) >>
        updateStateRemoveMissingFacilitators(
          key,
          state,
          responded.toSet,
          consensusStorage,
          clusterStorage,
          resources
        ) >>
        logger.info(s"Requesting state update after stall recovery {key=${key.toString}}") >>
        queue.requestStateUpdate(key)
    } else {
      logger.debug(s"No unresponsive peers to remove {key=${key.toString}}")
    }
  }

  private def getRespondedPeers[
    Artifact,
    Kind,
    Status
  ](
    resources: ConsensusResources[Artifact, Kind],
    ackKind: Kind,
    consensusOps: ConsensusOps[Status, Kind]
  ): List[PeerId] = {
    val getter = consensusOps.kindGetter(ackKind)

    resources.peerDeclarationsMap.collect {
      case (peerId, declarations) if getter(declarations).isDefined => peerId
    }.toList
  }

  private def updateStateRemoveMissingFacilitators[
    F[_]: Monad,
    Key,
    Artifact,
    Context,
    Status,
    OutcomeC,
    Kind
  ](
    key: Key,
    state: ConsensusState[Key, Status, OutcomeC, Kind],
    responded: Set[PeerId],
    consensusStorage: ConsensusStorage[F, _, Key, Artifact, Context, Status, OutcomeC, Kind],
    clusterStorage: ClusterStorage[F],
    resources: ConsensusResources[Artifact, Kind]
  ): F[Unit] = {

    def computeMissingPeers: F[List[PeerId]] =
      state.facilitators.value.traverseFilter { pid =>
        for {
          maybePeer <- clusterStorage.getPeer(pid)
          stillResponsive = maybePeer.exists(_.isResponsive)
          wasActive = resources.peerDeclarationsMap.contains(pid)
          didNotRespond = !responded.contains(pid)

          shouldRemove =
            didNotRespond &&
              !stillResponsive &&
              !wasActive

        } yield if (shouldRemove) Some(pid) else None
      }

    computeMissingPeers.flatMap { missingPeers =>
      if (missingPeers.isEmpty) {
        Applicative[F].unit
      } else {
        consensusStorage
          .condModifyState(key) { maybeState =>
            maybeState.flatTraverse { currentState =>
              if (currentState.status == state.status) {
                val updatedState = currentState.copy(
                  facilitators = Facilitators(
                    currentState.facilitators.value.filter(responded.contains)
                  ),
                  removedFacilitators = RemovedFacilitators(
                    currentState.removedFacilitators.value ++ missingPeers
                  ),
                  lockStatus = LockStatus.Open
                )

                (updatedState.some, ()).some.pure[F]
              } else {
                none[(Option[ConsensusState[Key, Status, OutcomeC, Kind]], Unit)]
                  .pure[F]
              }
            }
          }
          .void
      }
    }
  }
}
