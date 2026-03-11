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

import eu.timepit.refined.auto._
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
      containsTriggerEvent <- storage.containsTriggerEvent

      _ <-
        if (maybeTimeTrigger.exists(currentTime >= _))
          queue.offer(ConsensusCommand.StartRound(Some(TimeTrigger)))
        else if (maybeTimeTrigger.isEmpty)
          scheduleTimeTrigger >> queue.offer(ConsensusCommand.StartRound(None))
        else if (containsTriggerEvent)
          queue.offer(ConsensusCommand.StartRound(Some(EventTrigger)))
        else
          Async[F].unit
    } yield ()

  private def afterTimeTrigger: F[Unit] =
    for {
      _ <- scheduleTimeTrigger
      containsTriggerEvent <- storage.containsTriggerEvent
      _ <- queue.offer(ConsensusCommand.StartRound(Some(EventTrigger))).whenA(containsTriggerEvent)
    } yield ()

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
      lockedForStatus: Boolean,
      noChangeCount: Int,
      stallCycleCount: Int,
      roundHadStall: Boolean,
      lastSummaryTime: FiniteDuration
    )

    val basePollInterval = 100L
    val maxPollInterval = 1000L

    case class ResourcesInfo(hash: Int, declaredCount: Int, activeCount: Int, missingPeerIds: Set[String])

    def getResourcesInfo(
      state: ConsensusState[Key, Status, Outcome, Kind],
      resources: ConsensusResources[Artifact, Kind]
    ): ResourcesInfo = {
      val active = state.facilitators.value.toSet -- state.withdrawnFacilitators.value
      val acksHash = resources.acksMap.size
      ops.maybeCollectingKind(state.status) match {
        case Some(kind) =>
          val getter = ops.kindGetter(kind)
          val respondedPeers = resources.peerDeclarationsMap.collect {
            case (pid, decls) if active.contains(pid) && getter(decls).isDefined => pid
          }.toSet
          val missingPeers = active -- respondedPeers
          ResourcesInfo(
            hash = (respondedPeers, acksHash).hashCode(),
            declaredCount = respondedPeers.size,
            activeCount = active.size,
            missingPeerIds = missingPeers.toList.map(_.value.value.take(8)).toSet
          )
        case None =>
          ResourcesInfo(
            hash = (resources.peerDeclarationsMap.keySet, acksHash).hashCode(),
            declaredCount = resources.peerDeclarationsMap.size,
            activeCount = active.size,
            missingPeerIds = Set.empty
          )
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

                info = getResourcesInfo(state, resources)
                currentHash = info.hash
                statusChanged = !ms.lastStatus.contains(state.status)
                resourcesChanged = currentHash != ms.lastResourcesHash
                isLocked = state.lockStatus === LockStatus.Closed
                reopened = state.lockStatus === LockStatus.Reopened && ms.lockedForStatus

                newStatusStartTime =
                  if (statusChanged) now
                  else if (reopened) now // Reset timer after unlock so re-stall waits reStallTimeout
                  else ms.statusStartTime
                statusDuration = now - newStatusStartTime
                newLockedForStatus =
                  if (statusChanged) false
                  else if (reopened) false
                  else ms.lockedForStatus
                // Reset stall cycles on status change OR successful unlock (reopened)
                // A successful unlock means the adaptive thresholds worked and we should
                // give the round a fresh stall budget to complete with the reduced facilitator set
                newStallCycleCount = if (statusChanged || reopened) 0 else ms.stallCycleCount

                // Track successful unlocks for observability
                _ <- Metrics[F].incrementCounter("dag_consensus_unlock_succeeded").whenA(reopened)

                _ <- queue.offer(ConsensusCommand.CheckUpdate(key)).whenA(resourcesChanged || statusChanged || isLocked)

                declarationTimeout <- getCurrentDeclarationTimeout
                // Graduated timeout based on declaration progress:
                // - Re-stall: short timeout after previous stall (we're in recovery mode)
                // - No progress: short timeout when no declarations received at all
                // - Near completion: extend timeout when >75% declared (wait for slow peers)
                // - Normal: standard timeout otherwise
                declarationProgress = if (info.activeCount > 0) info.declaredCount.toDouble / info.activeCount else 0.0
                nearCompletion = declarationProgress >= 0.75 && info.declaredCount < info.activeCount
                effectiveTimeout =
                  if (ms.stallCycleCount > 0 || ms.roundHadStall)
                    config.reStallTimeout.getOrElse(declarationTimeout)
                  else if (info.declaredCount == 0)
                    config.noProgressTimeout.getOrElse(declarationTimeout)
                  else if (nearCompletion)
                    // Give 50% more time when we're close - high latency peers might just be slow
                    declarationTimeout + (declarationTimeout / 2)
                  else
                    declarationTimeout
                withinStallBudget = ms.stallCycleCount < config.maxStallCycles

                didLock <- handleStall(
                  key = key,
                  state = state,
                  declarationTimeout = effectiveTimeout,
                  statusDuration = statusDuration,
                  alreadyLocked = newLockedForStatus || !withinStallBudget,
                  declaredCount = info.declaredCount,
                  activeCount = info.activeCount,
                  missingPeerIds = info.missingPeerIds
                )

                // Detect failed stall cycle: locked, still Closed (unlock hasn't succeeded),
                // re-stall timeout elapsed, within budget. Re-spread ACKs for another propagation attempt.
                failedStallCycle = ms.lockedForStatus && isLocked && statusDuration >= effectiveTimeout &&
                  withinStallBudget && !statusChanged
                _ <- (spreadAckIfCollecting(key, state) >>
                  queue.offer(ConsensusCommand.CheckUpdate(key))).whenA(failedStallCycle)

                // Reset timer for next cycle when a failed stall cycle is counted
                adjustedStatusStartTime = if (failedStallCycle) now else newStatusStartTime

                // Count cycles: fresh locks (after status change or Reopened reset) AND failed re-stall attempts
                finalStallCycleCount =
                  if (didLock && !ms.lockedForStatus) newStallCycleCount + 1
                  else if (failedStallCycle) newStallCycleCount + 1
                  else newStallCycleCount
                newRoundHadStall = ms.roundHadStall || didLock

                // Abandon round if stall budget exhausted and still locked (unlock never succeeded)
                shouldAbandon = finalStallCycleCount >= config.maxStallCycles && isLocked

                _ <- (
                  logger.error(
                    s"Round key=$key stuck after $finalStallCycleCount stall cycles in Closed state, abandoning round"
                  ) >>
                    Metrics[F].incrementCounter("dag_consensus_round_abandoned") >>
                    // Remove stale Closed state so the next round can start fresh with the same key.
                    // Only removes if still Closed — if another fiber unlocked it in the meantime, leave it.
                    storage
                      .condModifyState[Unit](key) {
                        case Some(s) if s.lockStatus === LockStatus.Closed =>
                          (none[ConsensusState[Key, Status, Outcome, Kind]], ()).some.pure[F]
                        case _ =>
                          none.pure[F]
                      }
                      .void >>
                    queue.offer(ConsensusCommand.RoundCompleted)
                ).whenA(shouldAbandon)

                // Periodic summary log every ~10s (avoids spam, provides useful state visibility)
                summaryInterval = 10.seconds
                timeSinceLastSummary = now - ms.lastSummaryTime
                shouldLogSummary = statusChanged || (timeSinceLastSummary >= summaryInterval && info.declaredCount < info.activeCount)
                newSummaryTime = if (shouldLogSummary) now else ms.lastSummaryTime
                statusName = state.status.getClass.getSimpleName.stripSuffix("$")
                lockInfo = if (isLocked) " LOCKED" else if (reopened) " REOPENED" else ""
                missingInfo = if (info.missingPeerIds.nonEmpty) s" missing=[${info.missingPeerIds.mkString(",")}]" else ""
                _ <- logger
                  .info(
                    s"Round key=$key status=$statusName declared=${info.declaredCount}/${info.activeCount} " +
                      s"elapsed=${statusDuration.toSeconds}s stallCycle=${finalStallCycleCount}$lockInfo$missingInfo"
                  )
                  .whenA(shouldLogSummary && !shouldAbandon)

                changed = resourcesChanged || statusChanged
                newNoChangeCount = if (changed) 0 else ms.noChangeCount + 1
                sleepMs = if (changed) basePollInterval else math.min(basePollInterval * (newNoChangeCount + 1), maxPollInterval)
                _ <- Temporal[F].sleep(sleepMs.millis).unlessA(shouldAbandon)

              } yield
                if (shouldAbandon)
                  Right(())
                else
                  Left(
                    MonitorState(
                      lastResourcesHash = currentHash,
                      lastStatus = Some(state.status),
                      statusStartTime = adjustedStatusStartTime,
                      lockedForStatus = didLock,
                      noChangeCount = newNoChangeCount,
                      stallCycleCount = finalStallCycleCount,
                      roundHadStall = newRoundHadStall,
                      lastSummaryTime = newSummaryTime
                    )
                  )
          }
      }

    for {
      now <- Async[F].monotonic
      _ <- Async[F].tailRecM(
        MonitorState(
          0,
          None,
          now,
          lockedForStatus = false,
          noChangeCount = 0,
          stallCycleCount = 0,
          roundHadStall = false,
          lastSummaryTime = now
        )
      )(monitorStep)
    } yield ()
  }

  private def handleStall(
    key: Key,
    state: ConsensusState[Key, Status, Outcome, Kind],
    declarationTimeout: FiniteDuration,
    statusDuration: FiniteDuration,
    alreadyLocked: Boolean,
    declaredCount: Int,
    activeCount: Int,
    missingPeerIds: Set[String]
  ): F[Boolean] = {
    val shouldLock = statusDuration >= declarationTimeout && !alreadyLocked

    if (shouldLock) {
      val statusName = state.status.getClass.getSimpleName.stripSuffix("$")
      val missingInfo =
        if (missingPeerIds.nonEmpty) s", missing=${missingPeerIds.mkString(",")}"
        else ""
      logger.warn(
        s"Stall detected at key=$key status=$statusName after ${statusDuration.toSeconds}s " +
          s"(timeout=${declarationTimeout.toSeconds}s), declared=$declaredCount/$activeCount$missingInfo, locking"
      ) >>
        Metrics[F].incrementCounter("dag_consensus_stall_detected") >>
        tryLockAndSpreadAck(key, state).as(true)
    } else {
      // Return whether we are genuinely in Closed state, not just budget-exhausted.
      // This prevents lockedForStatus oscillation after maxStallCycles is reached
      // when state is Reopened (which would trigger spurious reopened detection each tick).
      (alreadyLocked && state.lockStatus === LockStatus.Closed).pure[F]
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
        logger.debug(s"Spreading ack for key=$key, kind=$ackKind") >>
          storage.getResources(key).flatMap { resources =>
            updater.trySpreadAck(key, ackKind, resources).void
          }
      case None =>
        Async[F].unit
    }
}
