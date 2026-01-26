package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.kernel.{Async, Temporal}
import cats.effect.std.Supervisor
import cats.kernel.{Eq, Next}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusResources
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics

import monocle.Lens

/** Handles consensus round facilitation, post-consensus scheduling, and stall detection.
  *
  * Runs a single stall detection loop per round (not per status) to avoid fiber leaks.
  *
  * @see
  *   StateTransitions for state advancement logic
  * @see
  *   ConsensusFSM for command routing
  */
class ConsensusRoundRunner[F[_]: Async: Metrics, Event, Key: Next, Artifact, Ctx, Status: Eq, Outcome, Kind](
  ctx: ConsensusEngineContext[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind]
)(implicit outcomeKey: Lens[Outcome, Key], supervisor: Supervisor[F]) {

  import ctx.{advancer, config, creator, logger, ops, queue, storage, updater}

  def runRound(trigger: Option[ConsensusTrigger]): F[Unit] =
    storage.getLastConsensusOutcome.flatMap {
      case None =>
        logger.warn("No previous outcome; cannot start round.") >>
          queue.offer(ConsensusCommand.RoundCompleted)

      case Some(outcome) =>
        val nextKey = outcomeKey.get(outcome).next
        logger.info(s"Facilitating consensus round at key=$nextKey with trigger=$trigger") >>
          facilitateRound(outcome, nextKey, trigger)
    }

  private def facilitateRound(lastOutcome: Outcome, key: Key, trigger: Option[ConsensusTrigger]): F[Unit] =
    for {
      resources <- storage.getResources(key)
      facilitated <- creator.tryFacilitateConsensus(key, lastOutcome, trigger, resources)

      _ <- facilitated match {
        case Some(_) =>
          logger.info(s"Facilitated consensus at key=$key") >>
            startRoundMonitor(key) >>
            doInitialCheck(key)

        case None =>
          handleExistingOrMissingState(key)
      }
    } yield ()

  private def handleExistingOrMissingState(key: Key): F[Unit] =
    storage.getState(key).flatMap {
      case Some(_) =>
        logger.debug(s"State already exists for key=$key, checking progress") >>
          startRoundMonitor(key) >>
          doInitialCheck(key)

      case None =>
        logger.warn(s"Could not facilitate and no existing state for key=$key") >>
          queue.offer(ConsensusCommand.RoundCompleted)
    }

  private def doInitialCheck(key: Key): F[Unit] =
    for {
      resources <- storage.getResources(key)
      maybeUpdate <- updater.tryUpdateConsensus(key, resources)

      _ <- maybeUpdate.traverse_ {
        case (_, newState) =>
          advancer.getConsensusOutcome(newState) match {
            case Some(_) => queue.offer(ConsensusCommand.CheckUpdate(key))
            case None    => logger.debug(s"Initial check: updated to ${newState.status}, waiting for declarations")
          }
      }
    } yield ()

  def afterConsensusFinish(majorityTrigger: ConsensusTrigger): F[Unit] =
    majorityTrigger match {
      case EventTrigger => afterEventTrigger
      case TimeTrigger  => afterTimeTrigger
    }

  private def afterEventTrigger: F[Unit] =
    for {
      maybeTimeTrigger <- storage.getTimeTrigger
      currentTime <- Async[F].monotonic

      _ <-
        if (maybeTimeTrigger.exists(currentTime >= _))
          queue.offer(ConsensusCommand.StartRound(Some(TimeTrigger)))
        else if (maybeTimeTrigger.isEmpty)
          scheduleTimeTrigger >> queue.offer(ConsensusCommand.StartRound(None))
        else
          Async[F].unit
    } yield ()

  private def afterTimeTrigger: F[Unit] =
    scheduleTimeTrigger

  private def scheduleTimeTrigger: F[Unit] =
    for {
      nextTime <- Async[F].monotonic.map(_ + config.timeTriggerInterval)
      _ <- storage.setTimeTrigger(nextTime)
      _ <- supervisor.supervise {
        Temporal[F].sleep(config.timeTriggerInterval) >>
          checkAndTriggerTime.handleErrorWith(err => logger.error(err)("Error triggering consensus with time trigger"))
      }
    } yield ()

  private def checkAndTriggerTime: F[Unit] =
    for {
      maybeTimeTrigger <- storage.getTimeTrigger
      currentTime <- Async[F].monotonic
      _ <- queue.offer(ConsensusCommand.TimeTick).whenA(maybeTimeTrigger.exists(currentTime >= _))
    } yield ()

  private def getCurrentDeclarationTimeout: F[FiniteDuration] =
    ctx.nodeStorage.isInJoiningGracePeriod.map { isInJoiningGracePeriod =>
      if (isInJoiningGracePeriod) config.timeTriggerInterval else config.declarationTimeout
    }

  private def startRoundMonitor(key: Key): F[Unit] =
    supervisor.supervise {
      roundMonitor(key)
        .handleErrorWith(err => logger.error(err)(s"Error in round monitor for key=$key"))
    }.void

  private def roundMonitor(key: Key): F[Unit] = {
    case class MonitorState(
      lastResourcesHash: Int,
      lastStatus: Option[Status],
      statusStartTime: FiniteDuration,
      lockedForStatus: Boolean
    )

    val pollInterval = 100.millis

    def getResourcesHash(
      state: ConsensusState[Key, Status, Outcome, Kind],
      resources: ConsensusResources[Artifact, Kind]
    ): Int = {
      val active = state.facilitators.value.toSet -- state.withdrawnFacilitators.value
      ops.maybeCollectingKind(state.status) match {
        case Some(kind) =>
          val getter = ops.kindGetter(kind)
          val respondedPeers = resources.peerDeclarationsMap.collect {
            case (pid, decls) if active.contains(pid) && getter(decls).isDefined => pid
          }
          respondedPeers.toSet.hashCode()
        case None =>
          resources.peerDeclarationsMap.keySet.hashCode()
      }
    }

    def monitorStep(ms: MonitorState): F[Either[MonitorState, Unit]] =
      storage.getState(key).flatMap {
        case None =>
          logger.debug(s"Round monitor: state gone for key=$key, stopping") >>
            Async[F].pure(Right(()))

        case Some(state) =>
          advancer.getConsensusOutcome(state) match {
            case Some(_) =>
              logger.debug(s"Round monitor: outcome ready for key=$key, stopping") >>
                Async[F].pure(Right(()))

            case None =>
              for {
                now <- Async[F].monotonic
                resources <- storage.getResources(key)

                currentHash = getResourcesHash(state, resources)
                statusChanged = !ms.lastStatus.contains(state.status)
                resourcesChanged = currentHash != ms.lastResourcesHash

                newStatusStartTime = if (statusChanged) now else ms.statusStartTime
                statusDuration = now - newStatusStartTime

                newLockedForStatus = if (statusChanged) false else ms.lockedForStatus

                _ <- queue.offer(ConsensusCommand.CheckUpdate(key)).whenA(resourcesChanged || statusChanged)

                declarationTimeout <- getCurrentDeclarationTimeout
                didLock <- handleStall(
                  key = key,
                  state = state,
                  declarationTimeout = declarationTimeout,
                  statusDuration = statusDuration,
                  alreadyLocked = newLockedForStatus
                )

                _ <- Temporal[F].sleep(pollInterval)

              } yield
                Left(
                  MonitorState(
                    lastResourcesHash = currentHash,
                    lastStatus = Some(state.status),
                    statusStartTime = newStatusStartTime,
                    lockedForStatus = didLock
                  )
                )
          }
      }

    for {
      now <- Async[F].monotonic
      _ <- Async[F].tailRecM(MonitorState(0, None, now, lockedForStatus = false))(monitorStep)
    } yield ()
  }

  private def handleStall(
    key: Key,
    state: ConsensusState[Key, Status, Outcome, Kind],
    declarationTimeout: FiniteDuration,
    statusDuration: FiniteDuration,
    alreadyLocked: Boolean
  ): F[Boolean] = {
    val shouldLock = statusDuration >= declarationTimeout && !alreadyLocked

    if (shouldLock) {
      logger.debug(s"Stall detected at key=$key after ${statusDuration.toSeconds}s, locking and spreading ack") >>
        tryLockAndSpreadAck(key, state).as(true)
    } else {
      alreadyLocked.pure[F]
    }
  }

  private def tryLockAndSpreadAck(
    key: Key,
    state: ConsensusState[Key, Status, Outcome, Kind]
  ): F[Unit] =
    updater.tryLockConsensus(key, state).flatMap {
      case Some((_, lockedState)) =>
        logger.info(s"Locked consensus at key=$key") >>
          spreadAckIfCollecting(key, lockedState) >>
          queue.offer(ConsensusCommand.CheckUpdate(key))

      case None =>
        logger.debug(s"Could not lock consensus at key=$key, spreading ack anyway") >>
          spreadAckIfCollecting(key, state) >>
          queue.offer(ConsensusCommand.CheckUpdate(key))
    }

  private def spreadAckIfCollecting(
    key: Key,
    state: ConsensusState[Key, Status, Outcome, Kind]
  ): F[Unit] =
    ops.maybeCollectingKind(state.status) match {
      case Some(ackKind) =>
        if (!state.spreadAckKinds.contains(ackKind)) {
          logger.debug(s"Spreading ack for key=$key, kind=$ackKind") >>
            storage.getResources(key).flatMap { resources =>
              updater.trySpreadAck(key, ackKind, resources).void
            }
        } else {
          Async[F].unit
        }
      case None =>
        Async[F].unit
    }
}
