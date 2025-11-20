package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.effect._
import cats.effect.std.Supervisor
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.config.types.ConsensusConfig

import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.Logger

private[consensus] case class StallDetectionHandle[F[_]](
  cancelSignal: Deferred[F, Unit],
  fiber: Fiber[F, Throwable, Unit]
)

private[consensus] object StallDetection {

  def scheduleStallDetection[
    F[_]: Async,
    Key,
    Artifact,
    Context,
    Status,
    OutcomeC,
    Kind
  ](
    key: Key,
    config: ConsensusConfig,
    stallDetectionRef: SignallingRef[F, Map[Key, StallDetectionHandle[F]]],
    consensusOps: ConsensusOps[Status, Kind],
    consensusStorage: ConsensusStorage[F, _, Key, Artifact, Context, Status, OutcomeC, Kind],
    consensusStateUpdater: ConsensusStateUpdater[F, Key, Artifact, Context, Status, OutcomeC, Kind],
    queue: ConsensusQueue[F, Key],
    logger: Logger[F],
    isFirstRoundAfterJoin: Boolean
  )(implicit S: Supervisor[F]): F[Unit] =
    for {
      _ <- cancelStallDetection(key, stallDetectionRef, logger)
      cancelSignal <- Deferred[F, Unit]

      sleepDuration =
        if (isFirstRoundAfterJoin) {
          60.seconds
        } else {
          config.declarationTimeout
        }

      stallDetectionAction =
        Temporal[F].sleep(sleepDuration) >>
          logger.warn(s"Stall detected for consensus round {key=${key.toString}}") >>
          processStallDetection(
            key,
            config,
            consensusOps,
            consensusStorage,
            consensusStateUpdater,
            queue,
            logger
          ) >>
          stallDetectionRef.update(_ - key)

      cancellationAction =
        cancelSignal.get >>
          logger.debug(s"Stall detection cancelled for {key=${key.toString}}")

      racedAction = Concurrent[F].race(stallDetectionAction, cancellationAction).void

      actionWithErrorHandling = racedAction.handleErrorWith { err =>
        logger.error(err)(s"Error in stall detection {key=${key.toString}}") >>
          stallDetectionRef.update(_ - key)
      }

      fiber <- S.supervise(actionWithErrorHandling)

      handle = StallDetectionHandle(cancelSignal, fiber)
      _ <- stallDetectionRef.update(_ + (key -> handle))
    } yield ()

  private def cancelStallDetection[F[_]: Async, Key](
    key: Key,
    stallDetectionRef: SignallingRef[F, Map[Key, StallDetectionHandle[F]]],
    logger: Logger[F]
  ): F[Unit] =
    stallDetectionRef.modify { handles =>
      handles.get(key) match {
        case Some(handle) =>
          val newMap = handles - key
          val cancelEffect = handle.cancelSignal
            .complete(())
            .void
            .handleErrorWith(err => logger.warn(err)(s"Failed to cancel stall detection for key $key"))
          (newMap, cancelEffect)

        case None =>
          (handles, Async[F].unit)
      }
    }.flatten

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
    config: ConsensusConfig,
    consensusOps: ConsensusOps[Status, Kind],
    consensusStorage: ConsensusStorage[F, _, Key, Artifact, Context, Status, OutcomeC, Kind],
    consensusStateUpdater: ConsensusStateUpdater[F, Key, Artifact, Context, Status, OutcomeC, Kind],
    queue: ConsensusQueue[F, Key],
    logger: Logger[F]
  ): F[Unit] =
    consensusStorage.getState(key).flatMap {
      case Some(currentState) =>
        consensusStateUpdater
          .tryLockConsensus(key, currentState)
          .flatMap {
            case Some((_, lockedState)) =>
              logger.info(s"Consensus locked for stall recovery {key=${key.toString}}") >>
                Temporal[F].sleep(config.lockDuration) >>
                consensusOps
                  .maybeCollectingKind(lockedState.status)
                  .traverse { (ackKind: Kind) =>
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
      case None =>
        logger.debug(s"State no longer exists for stall recovery {key=${key.toString}}")
    }
}
