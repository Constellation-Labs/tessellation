package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.kernel.{Async, Deferred, Temporal}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusResources
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics

import eu.timepit.refined.auto._

/** Monitors a consensus round for stalls and manages the lock/unlock lifecycle.
  *
  * ==Stall Detection Flow==
  * {{{
  *   Wait declarationTimeout
  *     → Lock (Closed) + spread ACKs
  *     → Wait for unlock (Reopened) via ACK voting
  *     → If unlock fails after reStallTimeout: re-spread ACKs (failed stall cycle)
  *     → After maxStallCycles: abandon round
  *     → After maxRoundDuration: abandon round (wall-clock safety net)
  * }}}
  *
  * Extracted from ConsensusRoundRunner for testability and separation of concerns.
  */
class StallDetector[F[_]: Async: Metrics, Event, Key, Artifact, Ctx, Status, Outcome, Kind](
  ctx: ConsensusEngineContext[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind]
) {

  import ctx.{config, logger, ops, queue, storage, updater}

  case class MonitorState(
    lastResourcesHash: Int,
    lastStatus: Option[Status],
    statusStartTime: FiniteDuration,
    roundStartTime: FiniteDuration,
    lockedForStatus: Boolean,
    noChangeCount: Int,
    stallCycleCount: Int,
    roundHadStall: Boolean,
    lastSummaryTime: FiniteDuration
  )

  private val basePollInterval = 100L
  private val maxPollInterval = 1000L

  case class ResourcesInfo(hash: Int, declaredCount: Int, activeCount: Int, missingPeerIds: Set[String])

  def monitor(key: Key, cancelSignal: Deferred[F, Unit]): F[Unit] =
    for {
      now <- Async[F].monotonic
      _ <- Async[F].race(
        cancelSignal.get,
        Async[F].tailRecM(
          MonitorState(
            lastResourcesHash = 0,
            lastStatus = None,
            statusStartTime = now,
            roundStartTime = now,
            lockedForStatus = false,
            noChangeCount = 0,
            stallCycleCount = 0,
            roundHadStall = false,
            lastSummaryTime = now
          )
        )(monitorStep(key, _))
      )
    } yield ()

  def monitorStep(key: Key, ms: MonitorState): F[Either[MonitorState, Unit]] =
    storage.getState(key).flatMap {
      case None =>
        logger.debug(s"Round monitor: state gone for key=$key, stopping") >>
          Async[F].pure(Right(()))

      case Some(state) =>
        ctx.advancer.getConsensusOutcome(state) match {
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
                else if (reopened) now
                else ms.statusStartTime
              statusDuration = now - newStatusStartTime
              newLockedForStatus =
                if (statusChanged) false
                else if (reopened) false
                else ms.lockedForStatus
              newStallCycleCount = if (statusChanged || reopened) 0 else ms.stallCycleCount

              _ <- queue.offer(ConsensusCommand.CheckUpdate(key)).whenA(resourcesChanged || statusChanged || isLocked)
              _ <- Metrics[F].incrementCounter("dag_consensus_unlock_success").whenA(reopened)

              declarationTimeout <- getCurrentDeclarationTimeout
              baseTimeout =
                if (ms.stallCycleCount > 0)
                  config.reStallTimeout.getOrElse(declarationTimeout)
                else if (info.declaredCount == 0)
                  config.noProgressTimeout.getOrElse(declarationTimeout)
                else
                  declarationTimeout
              declarationProgress = if (info.activeCount > 0) info.declaredCount.toDouble / info.activeCount else 0.0
              nearCompletion = declarationProgress >= 0.75 && info.declaredCount < info.activeCount
              effectiveTimeout =
                if (nearCompletion && ms.stallCycleCount == 0)
                  baseTimeout + (baseTimeout / 2)
                else baseTimeout
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

              failedStallCycle = ms.lockedForStatus && isLocked && statusDuration >= effectiveTimeout &&
                withinStallBudget && !statusChanged
              _ <- (spreadAckIfCollecting(key, state) >>
                queue.offer(ConsensusCommand.CheckUpdate(key))).whenA(failedStallCycle)

              freshLock = didLock && !ms.lockedForStatus
              adjustedStatusStartTime = if (failedStallCycle || freshLock) now else newStatusStartTime

              finalStallCycleCount =
                if (didLock && !ms.lockedForStatus) newStallCycleCount + 1
                else if (failedStallCycle) newStallCycleCount + 1
                else newStallCycleCount
              newRoundHadStall = ms.roundHadStall || didLock

              roundElapsed = now - ms.roundStartTime
              roundTimedOut = config.maxRoundDuration.exists(roundElapsed >= _)
              shouldAbandon = (finalStallCycleCount >= config.maxStallCycles && isLocked) || roundTimedOut

              abandonReason =
                if (roundTimedOut)
                  s"round timed out after ${roundElapsed.toSeconds}s (max=${config.maxRoundDuration.map(_.toSeconds)}s)"
                else s"stuck after $finalStallCycleCount stall cycles in Closed state"

              _ <- (
                logger.error(
                  s"Round key=$key $abandonReason, abandoning round"
                ) >>
                  Metrics[F].incrementCounter("dag_consensus_round_abandoned") >>
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
                    roundStartTime = ms.roundStartTime,
                    lockedForStatus = didLock,
                    noChangeCount = newNoChangeCount,
                    stallCycleCount = finalStallCycleCount,
                    roundHadStall = newRoundHadStall,
                    lastSummaryTime = newSummaryTime
                  )
                )
        }
    }

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

  def handleStall(
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
      (alreadyLocked && state.lockStatus === LockStatus.Closed).pure[F]
    }
  }

  def tryLockAndSpreadAck(
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

  def spreadAckIfCollecting(
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

  private def getCurrentDeclarationTimeout: F[FiniteDuration] =
    ctx.nodeStorage.isInJoiningGracePeriod.map { isInJoiningGracePeriod =>
      if (isInJoiningGracePeriod) config.timeTriggerInterval else config.declarationTimeout
    }
}
