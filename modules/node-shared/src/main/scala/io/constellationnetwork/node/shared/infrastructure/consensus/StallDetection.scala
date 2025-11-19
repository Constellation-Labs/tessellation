package io.constellationnetwork.node.shared.infrastructure.consensus

import cats._
import cats.effect._
import cats.effect.std.Supervisor
import cats.syntax.all._

import scala.concurrent.duration._

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
    queue: ConsensusQueue[F, Key],
    logger: Logger[F],
    isFirstRoundAfterJoin: Boolean
  )(implicit S: Supervisor[F]): F[Unit] =
    Clock[F].monotonic.flatMap { now =>
      val stallId = now.toMillis

      stallDetectionRef.update(_ + (key -> stallId)) >>
        S.supervise {
          val sleepDuration = if (isFirstRoundAfterJoin) {
            60.seconds
          } else {
            config.declarationTimeout
          }
          Temporal[F].sleep(sleepDuration) >>
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
                  _ <- logger.info(s"Requesting state update after spreading ACKs {key=${key.toString}}")
                  _ <- queue.requestStateUpdate(key)
                } yield ()
              }
              .void

        case None =>
          logger.debug(s"Could not lock consensus for stall recovery {key=${key.toString}}")
      }
}
