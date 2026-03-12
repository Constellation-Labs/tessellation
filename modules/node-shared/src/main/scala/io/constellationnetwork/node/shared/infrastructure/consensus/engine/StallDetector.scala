package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.kernel.{Async, Deferred, Temporal}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusResources
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics

import eu.timepit.refined.auto._

/** Monitors a consensus round for stalls and manages recovery.
  *
  * ==Stall Detection Flow==
  * {{{
  *   Wait declarationTimeout
  *     → If in proposal phase: view change (new leader)
  *     → Otherwise: abandon round after maxStallCycles
  *     → After maxRoundDuration: abandon round (wall-clock safety net)
  * }}}
  *
  * ==View Change==
  *
  * When the leader fails to propose within the timeout, the view number is incremented and a new leader is selected deterministically using
  * rendezvous hashing. The new leader's advanceFromProposals will detect that it should spread its proposal.
  *
  * ==Abandon==
  *
  * For non-proposal phases (facilities, signatures, etc.), stalls indicate slow peers rather than leader failure. After maxStallCycles
  * timeouts, the round is abandoned and a fresh round starts.
  */
class StallDetector[F[_]: Async: Metrics, Event, Key, Artifact, Ctx, Status, Outcome, Kind](
  ctx: ConsensusEngineContext[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind]
) {

  import ctx.{config, logger, ops, peerQualityTracker, queue, storage}

  private case class MonitorState(
    lastResourcesHash: Int,
    lastStatus: Option[Status],
    statusStartTime: FiniteDuration,
    roundStartTime: FiniteDuration,
    noChangeCount: Int,
    stallCount: Int,
    lastSummaryTime: FiniteDuration,
    lastScoreLogTime: FiniteDuration
  )

  private val basePollInterval = 100L
  private val maxPollInterval = 1000L

  private case class ResourcesInfo(hash: Int, declaredCount: Int, activeCount: Int, missingPeerIds: Set[String])

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
            noChangeCount = 0,
            stallCount = 0,
            lastSummaryTime = now,
            lastScoreLogTime = now
          )
        )(monitorStep(key, _))
      )
    } yield ()

  private def monitorStep(key: Key, ms: MonitorState): F[Either[MonitorState, Unit]] =
    storage.getState(key).flatMap {
      case None =>
        logger.debug(s"[CONSENSUS] Round monitor: state gone for key=$key, stopping") >>
          Async[F].pure(Right(()))

      case Some(state) =>
        ctx.advancer.getConsensusOutcome(state) match {
          case Some(_) =>
            logger.debug(s"[CONSENSUS] Round monitor: outcome ready for key=$key, stopping") >>
              Async[F].pure(Right(()))

          case None =>
            for {
              now <- Async[F].monotonic
              resources <- storage.getResources(key)

              info = getResourcesInfo(state, resources)
              currentHash = info.hash
              statusChanged = !ms.lastStatus.contains(state.status)
              resourcesChanged = currentHash != ms.lastResourcesHash

              newStatusStartTime = if (statusChanged) now else ms.statusStartTime
              statusDuration = now - newStatusStartTime
              newStallCount = if (statusChanged) 0 else ms.stallCount

              _ <- queue.offer(ConsensusCommand.CheckUpdate(key)).whenA(resourcesChanged || statusChanged)

              declarationTimeout <- getCurrentDeclarationTimeout
              baseTimeout =
                if (ms.stallCount > 0)
                  config.reStallTimeout.getOrElse(declarationTimeout)
                else if (info.declaredCount == 0)
                  config.noProgressTimeout.getOrElse(declarationTimeout)
                else
                  declarationTimeout
              declarationProgress = if (info.activeCount > 0) info.declaredCount.toDouble / info.activeCount else 0.0
              nearCompletion = declarationProgress >= 0.75 && info.declaredCount < info.activeCount
              baseEffectiveTimeout =
                if (nearCompletion && ms.stallCount == 0)
                  baseTimeout + (baseTimeout / 2)
                else baseTimeout

              // Reduce patience for known-bad leaders during proposal phase
              leaderQuality <- peerQualityTracker.getQualityScore(state.leader)
              effectiveTimeout =
                if (ops.isProposalPhase(state.status) && leaderQuality < config.leaderQualityThreshold)
                  FiniteDuration((baseEffectiveTimeout.toMillis * config.leaderQualityTimeoutMultiplier).toLong, MILLISECONDS)
                else baseEffectiveTimeout

              // Handle stall: view change for proposal phase, or count towards abandon
              didViewChange <- handleStall(
                key = key,
                state = state,
                declarationTimeout = effectiveTimeout,
                statusDuration = statusDuration,
                alreadyHandled = newStallCount > 0 && !statusChanged,
                declaredCount = info.declaredCount,
                activeCount = info.activeCount,
                missingPeerIds = info.missingPeerIds
              )

              adjustedStatusStartTime = if (didViewChange) now else newStatusStartTime
              finalStallCount =
                if (didViewChange) newStallCount + 1
                else newStallCount

              _ <- Metrics[F].updateGauge("dag_consensus_stall_cycle", finalStallCount)
              _ <- Metrics[F].updateGauge("dag_consensus_stall_declaration_progress", declarationProgress)

              roundElapsed = now - ms.roundStartTime
              _ <- Metrics[F].updateGauge("dag_consensus_round_elapsed_seconds", roundElapsed.toSeconds.toInt)
              roundTimedOut = config.maxRoundDuration.exists(roundElapsed >= _)
              shouldAbandon = finalStallCount >= config.maxStallCycles || roundTimedOut

              abandonReason =
                if (roundTimedOut)
                  s"round timed out after ${roundElapsed.toSeconds}s (max=${config.maxRoundDuration.map(_.toSeconds)}s)"
                else s"stuck after $finalStallCount stall cycles"

              _ <- abandonRound(key, abandonReason).whenA(shouldAbandon)

              summaryInterval = 10.seconds
              timeSinceLastSummary = now - ms.lastSummaryTime
              shouldLogSummary = statusChanged || (timeSinceLastSummary >= summaryInterval && info.declaredCount < info.activeCount)
              newSummaryTime = if (shouldLogSummary) now else ms.lastSummaryTime
              statusName = state.status.getClass.getSimpleName.stripSuffix("$")
              withdrawnCount = state.withdrawnFacilitators.value.size
              roundElapsedTotal = now - ms.roundStartTime
              _ <- logger
                .info(
                  s"[CONSENSUS] Round monitor\n" +
                    s"  key=$key status=$statusName declared=${info.declaredCount}/${info.activeCount}\n" +
                    s"  elapsed=${statusDuration.toSeconds}s roundElapsed=${roundElapsedTotal.toSeconds}s stallCount=$finalStallCount\n" +
                    s"  leader=${state.leader.show.take(8)}... leaderScore=${f"$leaderQuality%.2f"} facilitators=${state.facilitators.value.size}" +
                    (if (state.viewNumber > 0) s" view=${state.viewNumber}" else "") +
                    (if (withdrawnCount > 0) s" withdrawn=$withdrawnCount" else "") +
                    (if (info.missingPeerIds.nonEmpty) s"\n  missing=[${info.missingPeerIds.mkString(",")}]" else "")
                )
                .whenA(shouldLogSummary && !shouldAbandon)

              // Peer quality score logging every 60 seconds
              scoreLogInterval = 60.seconds
              timeSinceLastScoreLog = now - ms.lastScoreLogTime
              shouldLogScores = timeSinceLastScoreLog >= scoreLogInterval
              newScoreLogTime = if (shouldLogScores) now else ms.lastScoreLogTime
              _ <- peerQualityTracker.getQualityScores.flatMap { scores =>
                if (scores.nonEmpty) {
                  val sorted = scores.toList.sortBy(-_._2)
                  val total = sorted.size
                  // Show top 3 and bottom 3 (if different) to keep log manageable
                  val top3 = sorted.take(3).map { case (pid, score) => s"${pid.show.take(8)}:${f"$score%.2f"}" }
                  val bottom3 = sorted.takeRight(3).map { case (pid, score) => s"${pid.show.take(8)}:${f"$score%.2f"}" }
                  val topIds = sorted.take(3).map(_._1).toSet
                  val bottomEntries = if (total > 6) bottom3.filterNot(e => topIds.exists(id => e.startsWith(id.show.take(8)))) else Nil
                  val display =
                    if (bottomEntries.nonEmpty) s"best=[${top3.mkString(",")}] worst=[${bottomEntries.mkString(",")}]"
                    else s"[${top3.mkString(",")}]"
                  logger.info(s"[CONSENSUS] Peer quality scores: $display trackedPeers=$total")
                } else logger.debug("[CONSENSUS] Peer quality scores: no peers tracked yet")
              }.whenA(shouldLogScores && !shouldAbandon)

              changed = resourcesChanged || statusChanged || didViewChange
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
                    noChangeCount = newNoChangeCount,
                    stallCount = finalStallCount,
                    lastSummaryTime = newSummaryTime,
                    lastScoreLogTime = newScoreLogTime
                  )
                )
        }
    }

  private def getResourcesInfo(
    state: ConsensusState[Key, Status, Outcome, Kind],
    resources: ConsensusResources[Artifact, Kind]
  ): ResourcesInfo = {
    val active = state.facilitators.value.toSet -- state.withdrawnFacilitators.value
    ops.maybeCollectingKind(state.status) match {
      case Some(kind) =>
        val getter = ops.kindGetter(kind)
        val respondedPeers = resources.peerDeclarationsMap.collect {
          case (pid, decls) if active.contains(pid) && getter(decls).isDefined => pid
        }.toSet
        val missingPeers = active -- respondedPeers
        ResourcesInfo(
          hash = respondedPeers.hashCode(),
          declaredCount = respondedPeers.size,
          activeCount = active.size,
          missingPeerIds = missingPeers.toList.map(_.value.value.take(8)).toSet
        )
      case None =>
        ResourcesInfo(
          hash = resources.peerDeclarationsMap.keySet.hashCode(),
          declaredCount = resources.peerDeclarationsMap.size,
          activeCount = active.size,
          missingPeerIds = Set.empty
        )
    }
  }

  /** Handle a stall condition. Returns true if a view change was performed. */
  private def handleStall(
    key: Key,
    state: ConsensusState[Key, Status, Outcome, Kind],
    declarationTimeout: FiniteDuration,
    statusDuration: FiniteDuration,
    alreadyHandled: Boolean,
    declaredCount: Int,
    activeCount: Int,
    missingPeerIds: Set[String]
  ): F[Boolean] = {
    val shouldHandle = statusDuration >= declarationTimeout && !alreadyHandled

    if (shouldHandle) {
      val statusName = state.status.getClass.getSimpleName.stripSuffix("$")
      val missingInfo =
        if (missingPeerIds.nonEmpty) s"\n  missing=[${missingPeerIds.mkString(",")}]"
        else ""

      if (ops.isProposalPhase(state.status)) {
        // Proposal phase stall: leader failed to propose → view change
        logger.warn(
          s"[CONSENSUS] Leader stall — performing view change\n" +
            s"  key=$key status=$statusName elapsed=${statusDuration.toSeconds}s timeout=${declarationTimeout.toSeconds}s\n" +
            s"  declared=$declaredCount/$activeCount leader=${state.leader.show.take(8)}... view=${state.viewNumber}" +
            missingInfo
        ) >>
          Metrics[F].incrementCounter("dag_consensus_view_change") >>
          performViewChange(key, state).as(true)
      } else {
        // Non-proposal stall: just log and count towards abandon
        logger.warn(
          s"[CONSENSUS] Stall detected\n" +
            s"  key=$key status=$statusName elapsed=${statusDuration.toSeconds}s timeout=${declarationTimeout.toSeconds}s\n" +
            s"  declared=$declaredCount/$activeCount" + missingInfo
        ) >>
          Metrics[F].incrementCounter("dag_consensus_stall_detected") >>
          true.pure[F]
      }
    } else {
      false.pure[F]
    }
  }

  /** Perform a view change: increment viewNumber, select new leader, update state. */
  private def performViewChange(
    key: Key,
    currentState: ConsensusState[Key, Status, Outcome, Kind]
  ): F[Unit] = {
    val newViewNumber = currentState.viewNumber + 1
    val newLeader = ctx.facilitatorSelector.selectLeader(
      currentState.facilitators.value,
      currentState.entropy,
      newViewNumber
    )

    logger.info(
      s"[CONSENSUS] View change\n" +
        s"  key=$key view=${currentState.viewNumber}→$newViewNumber\n" +
        s"  leader=${currentState.leader.show.take(8)}...→${newLeader.show.take(8)}... facilitators=${currentState.facilitators.value.size}"
    ) >>
      peerQualityTracker.recordViewChange(currentState.leader) >>
      Metrics[F].updateGauge("dag_consensus_view_number", newViewNumber) >>
      storage
        .condModifyState[Unit](key) {
          case Some(state) if state.viewNumber === currentState.viewNumber =>
            val updated: ConsensusState[Key, Status, Outcome, Kind] =
              state.copy(viewNumber = newViewNumber, leader = newLeader)
            (updated.some, ()).some.pure[F]
          case _ =>
            none[(Option[ConsensusState[Key, Status, Outcome, Kind]], Unit)].pure[F]
        }
        .void >>
      queue.offer(ConsensusCommand.CheckUpdate(key))
  }

  private def abandonRound(key: Key, reason: String): F[Unit] =
    logger.error(s"[CONSENSUS] ABANDONING round key=$key reason=$reason") >>
      Metrics[F].incrementCounter("dag_consensus_round_abandoned") >>
      storage
        .condModifyState[Unit](key) {
          case Some(state) =>
            peerQualityTracker
              .recordRoundAbandoned(state.facilitators.value.toSet)
              .as((none[ConsensusState[Key, Status, Outcome, Kind]], ()).some)
          case _ =>
            none[(Option[ConsensusState[Key, Status, Outcome, Kind]], Unit)].pure[F]
        }
        .void >>
      queue.offer(ConsensusCommand.RoundCompleted) >>
      queue.offer(ConsensusCommand.TimeTick)

  private def getCurrentDeclarationTimeout: F[FiniteDuration] =
    ctx.nodeStorage.isInJoiningGracePeriod.map { isInJoiningGracePeriod =>
      if (isInJoiningGracePeriod) config.timeTriggerInterval else config.declarationTimeout
    }
}
