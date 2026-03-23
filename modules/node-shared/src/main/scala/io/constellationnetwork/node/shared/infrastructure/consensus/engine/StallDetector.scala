package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.Order
import cats.effect.kernel._
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.{ConsensusLog, ConsensusResources}
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.{PeerId, PeerResponsiveness, Unresponsive}

import eu.timepit.refined.auto._

/** Monitors a consensus round for stalls and manages recovery.
  *
  * ==Architecture==
  *
  * StallDetector is the orchestrator that polls state periodically and delegates to focused components:
  *   - '''ViewChangeManager''': deterministic leader re-election on proposal stalls
  *   - '''AbandonmentTracker''': consecutive failure tracking, resource cleanup, recovery download
  *   - '''ConsensusHealthStatus''': observable health snapshot for HTTP endpoint + metrics
  *
  * ==Stall Detection Flow==
  * {{{
  *   Poll (100ms-1000ms adaptive)
  *     → Detect status/resource changes → queue CheckUpdate
  *     → Calculate phase-adaptive timeout
  *     → If leader unresponsive → early view change (ViewChangeManager)
  *     → If timeout exceeded:
  *         → Proposal phase: view change (ViewChangeManager)
  *         → Other phases: count toward abandon
  *     → After maxStallCycles or maxRoundDuration → abandon (AbandonmentTracker)
  *     → Update health snapshot on each cycle
  * }}}
  */
@scala.annotation.nowarn("msg=type parameter Outcome.*shadows")
class StallDetector[F[_]: Async: Metrics, Event, Key: Order, Artifact, Ctx, Status, Outcome, Kind](
  ctx: ConsensusEngineContext[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind],
  viewChangeManager: ViewChangeManager[F, Key, Status, Outcome, Kind],
  abandonmentTracker: AbandonmentTracker[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind],
  healthRef: Ref[F, ConsensusHealthStatus],
  evictionVoteTracker: EvictionVoteTracker[F]
) {

  import ctx.{clusterStorage, config, logger, ops, peerQualityTracker, queue, selfId, storage}

  private def selfRole(state: ConsensusState[Key, Status, Outcome, Kind]): String =
    ConsensusLog.role(selfId, state.leader)

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

  private case class ResourcesInfo(hash: Int, declaredCount: Int, activeCount: Int, missingPeerIds: Set[String], missingPeers: Set[PeerId])

  def monitor(key: Key, cancelSignal: Deferred[F, Unit]): F[Unit] =
    for {
      now <- Async[F].monotonic
      _ <- viewChangeManager.resetSkippedEvictions
      _ <- evictionVoteTracker.clearVotes
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
        ConsensusLog.debug(logger, ConsensusLog.Lifecycle, key.toString, "n/a", "event" -> "MONITOR_STATE_GONE") >>
          healthRef.update(_.copy(isRunning = false, key = None, phase = None)) >>
          Async[F].pure(Right(()))

      case Some(state) =>
        ctx.advancer.getConsensusOutcome(state) match {
          case Some(_) =>
            ConsensusLog.debug(logger, ConsensusLog.Lifecycle, key.toString, "n/a", "event" -> "MONITOR_OUTCOME_READY") >>
              Async[F].pure(Right(()))

          case None =>
            runMonitorCycle(key, ms, state)
        }
    }

  /** Core monitoring cycle: detect changes, check timeouts, handle stalls, update health. */
  private def runMonitorCycle(
    key: Key,
    ms: MonitorState,
    state: ConsensusState[Key, Status, Outcome, Kind]
  ): F[Either[MonitorState, Unit]] =
    for {
      now <- Async[F].monotonic
      resources <- storage.getResources(key)

      info = getResourcesInfo(state, resources)
      statusChanged = !ms.lastStatus.contains(state.status)
      resourcesChanged = info.hash != ms.lastResourcesHash

      newStatusStartTime = if (statusChanged) now else ms.statusStartTime
      statusDuration = now - newStatusStartTime
      newStallCount = if (statusChanged) 0 else ms.stallCount

      _ <- queue.offer(ConsensusCommand.CheckUpdate(key)).whenA(resourcesChanged || statusChanged)

      // --- Timeout calculation ---
      effectiveTimeout <- calculateTimeout(ms.stallCount, info, state)

      // --- Early view change for unresponsive leader ---
      leaderUnresponsive <- isLeaderUnresponsive(state.leader)
      earlyViewChange = leaderUnresponsive && ops.isProposalPhase(state.status) && ms.stallCount == 0
      _ <- (
        ConsensusLog.warn(
          logger,
          ConsensusLog.Stall,
          key.toString,
          selfRole(state),
          "event" -> "EARLY_VIEW_CHANGE",
          "leader" -> ConsensusLog.pid(state.leader),
          "reason" -> "leader_unresponsive"
        ) >>
          viewChangeManager.performViewChange(key, state)
      ).whenA(earlyViewChange)

      // --- Handle stall: view change for proposal phase, count toward abandon for others ---
      stallResult <-
        if (earlyViewChange) StallResult(didStall = true, quorumInfeasible = false).pure[F]
        else
          handleStall(
            key = key,
            state = state,
            declarationTimeout = effectiveTimeout,
            statusDuration = statusDuration,
            declaredCount = info.declaredCount,
            activeCount = info.activeCount,
            missingPeerIds = info.missingPeerIds,
            missingPeers = info.missingPeers,
            stallCount = newStallCount
          )

      didStall = stallResult.didStall
      adjustedStatusStartTime = if (didStall) now else newStatusStartTime
      finalStallCount = if (didStall) newStallCount + 1 else newStallCount

      _ <- Metrics[F].updateGauge("dag_consensus_stall_cycle", finalStallCount)

      declarationProgress = if (info.activeCount > 0) info.declaredCount.toDouble / info.activeCount else 0.0
      _ <- Metrics[F].updateGauge("dag_consensus_stall_declaration_progress", declarationProgress)

      // --- Lagging node detection ---
      // If majority of registered peers are at a STRICTLY HIGHER key,
      // this node is lagging behind the network. Abandon immediately to trigger recovery.
      // IMPORTANT: Only peers at HIGHER keys indicate this node is behind.
      // Peers at the same or lower keys are stale registrations (e.g. from PeerObserved
      // using lastOutcome key, which is always one behind the current round key).
      // Using =!= (any different) instead of > caused cluster-wide cascade failures:
      // all nodes simultaneously detected "lagging" from stale registrations at the
      // previous ordinal, triggering mass recovery downloads with no peers to serve.
      //
      // CR9: Only count peers in Ready state for lagging detection.
      // Peers in Observing/Downloading states report stale observation keys from
      // pre-rollback/pre-download state (trySetObservationKey is set-if-empty, never
      // overwrites). The peerRegistrationStream re-populates registrations from these
      // stale keys immediately after clearAllPeerRegistrations, causing false lagging
      // detection on rollback nodes. Only Ready peers are actively participating in
      // consensus and have accurate keys.
      // TODO: These two Ref reads are non-atomic — peer registrations and responsive peers
      // could observe inconsistent state. The race is benign: worst case is one extra round
      // of lagging detection before self-correcting on the next monitor cycle.
      peerRegs <- storage.getPeerRegistrations
      responsivePeers <- clusterStorage.getResponsivePeers
      readyPeerIds = responsivePeers.filter(_.state === NodeState.Ready).map(_.id).toSet
      readyPeerRegs = peerRegs.view.filterKeys(readyPeerIds.contains).toMap
      peersAtHigherKey = readyPeerRegs.count { case (_, peerKey) => peerKey > key }
      totalRegisteredPeers = readyPeerRegs.size
      isLagging = totalRegisteredPeers >= 3 && peersAtHigherKey > totalRegisteredPeers / 2
      totalAllRegs = peerRegs.size
      _ <- (
        ConsensusLog.warn(
          logger,
          ConsensusLog.Stall,
          key.toString,
          selfRole(state),
          "event" -> "LAGGING_NODE_DETECTED",
          "peersAtHigherKey" -> peersAtHigherKey.toString,
          "totalReady" -> totalRegisteredPeers.toString,
          "totalAllRegs" -> totalAllRegs.toString,
          "ownKey" -> key.toString
        ) >>
          Metrics[F].incrementCounter("dag_consensus_lagging_node_detected")
      ).whenA(isLagging)

      // --- Quorum feasibility from handleStall ---
      // handleStall already computed quorum feasibility using the cluster-size-based floor.
      // When eviction would breach quorum, it skips the eviction and propagates quorumInfeasible=true.
      quorumInfeasible = stallResult.quorumInfeasible

      // --- View change loop escalation check ---
      // If repeated eviction attempts are skipped (below minimum facilitators), the view change
      // loop is cycling view numbers without progress. Escalate to abandonment.
      evictionLoopStuck <- viewChangeManager.shouldEscalateToAbandon

      // --- Round timeout / abandon check ---
      roundElapsed = now - ms.roundStartTime
      _ <- Metrics[F].updateGauge("dag_consensus_round_elapsed_seconds", roundElapsed.toSeconds.toInt)
      roundTimedOut = config.maxRoundDuration.exists(roundElapsed >= _)
      shouldAbandon = finalStallCount >= config.maxStallCycles || roundTimedOut || quorumInfeasible || isLagging || evictionLoopStuck

      abandonReason: AbandonReason =
        if (isLagging) AbandonReason.Lagging(peersAtHigherKey, totalRegisteredPeers, totalAllRegs)
        else if (quorumInfeasible)
          AbandonReason.QuorumInfeasible(stallResult.activeFacilitators, stallResult.quorumSize, stallResult.clusterSize)
        else if (evictionLoopStuck) AbandonReason.EvictionLoopStuck
        else if (roundTimedOut) AbandonReason.RoundTimeout(roundElapsed.toSeconds, config.maxRoundDuration.map(_.toSeconds))
        else AbandonReason.MaxStalls(finalStallCount)

      _ <- (
        peerQualityTracker.recordAbandonedMissingPeers(info.missingPeers).whenA(info.missingPeers.nonEmpty) >>
          ConsensusLog
            .info(
              logger,
              ConsensusLog.Facilitator,
              key.toString,
              selfRole(state),
              "event" -> "RECORDING_MISSING_PEERS",
              "count" -> info.missingPeers.size.toString
            )
            .whenA(info.missingPeers.nonEmpty) >>
          abandonmentTracker.abandonRound(key, abandonReason)
      ).whenA(shouldAbandon)

      // --- Update health snapshot ---
      statusName = state.status.getClass.getSimpleName.stripSuffix("$")
      _ <- healthRef.update(
        _.copy(
          key = key.toString.some,
          phase = statusName.some,
          phaseIndex = ops.phaseIndex(state.status).some,
          facilitatorCount = state.facilitators.value.size,
          declaredCount = info.declaredCount,
          activeCount = info.activeCount,
          leader = ConsensusLog.pid(state.leader).some,
          viewNumber = state.viewNumber,
          roundElapsedMs = roundElapsed.toMillis,
          phaseElapsedMs = statusDuration.toMillis,
          stallCount = finalStallCount,
          isRunning = true,
          missingPeers = info.missingPeers.toList.map(ConsensusLog.pid),
          facilitatorIds = state.facilitators.value.map(ConsensusLog.pid)
        )
      )

      // --- Periodic summary logging ---
      timeSinceLastSummary = now - ms.lastSummaryTime
      shouldLogSummary = statusChanged || (timeSinceLastSummary >= config.monitorSummaryInterval && info.declaredCount < info.activeCount)
      newSummaryTime = if (shouldLogSummary) now else ms.lastSummaryTime
      _ <- logSummary(key, state, info, statusDuration, roundElapsed, finalStallCount, statusName)
        .whenA(shouldLogSummary && !shouldAbandon)

      // --- Periodic peer quality score logging ---
      timeSinceLastScoreLog = now - ms.lastScoreLogTime
      shouldLogScores = timeSinceLastScoreLog >= config.peerScoreLogInterval
      newScoreLogTime = if (shouldLogScores) now else ms.lastScoreLogTime
      _ <- logPeerQualityScores(key, selfRole(state)).whenA(shouldLogScores && !shouldAbandon)

      // --- Adaptive sleep ---
      changed = resourcesChanged || statusChanged || didStall
      newNoChangeCount = if (changed) 0 else ms.noChangeCount + 1
      sleepMs = if (changed) basePollInterval else math.min(basePollInterval * (newNoChangeCount + 1), maxPollInterval)
      _ <- Temporal[F].sleep(sleepMs.millis).unlessA(shouldAbandon)

    } yield
      if (shouldAbandon)
        Right(())
      else
        Left(
          MonitorState(
            lastResourcesHash = info.hash,
            lastStatus = Some(state.status),
            statusStartTime = adjustedStatusStartTime,
            roundStartTime = ms.roundStartTime,
            noChangeCount = newNoChangeCount,
            stallCount = finalStallCount,
            lastSummaryTime = newSummaryTime,
            lastScoreLogTime = newScoreLogTime
          )
        )

  // ── Timeout Calculation ───────────────────────────────────────────

  private def calculateTimeout(
    stallCount: Int,
    info: ResourcesInfo,
    state: ConsensusState[Key, Status, Outcome, Kind]
  ): F[FiniteDuration] =
    for {
      declarationTimeout <- getCurrentDeclarationTimeout
      // noProgressTimeout only applies in facilities phase (phase index 0) where no declarations at all
      // means peers haven't started. In other phases (proposals, signatures), the standard phase timeout
      // should apply — e.g., in proposals phase the leader may be creating a complex artifact.
      isFacilitiesPhase = ops.phaseIndex(state.status) == 0
      baseTimeout =
        if (stallCount > 0)
          config.reStallTimeout.getOrElse(declarationTimeout)
        else if (info.declaredCount == 0 && isFacilitiesPhase)
          config.noProgressTimeout.getOrElse(declarationTimeout)
        else
          declarationTimeout

      declarationProgress = if (info.activeCount > 0) info.declaredCount.toDouble / info.activeCount else 0.0
      nearCompletion = declarationProgress >= 0.75 && info.declaredCount < info.activeCount

      // Skip near-completion timeout bonus when all missing peers are Unresponsive.
      // Waiting longer for peers that are known-unreachable just delays stall detection.
      allMissingUnresponsive <-
        if (nearCompletion && info.missingPeers.nonEmpty)
          info.missingPeers.toList.forallM { pid =>
            clusterStorage.getPeer(pid).map {
              case Some(peer) => peer.responsiveness === (Unresponsive: PeerResponsiveness)
              case None       => true // Unknown peer treated as unresponsive
            }
          }
        else false.pure[F]

      baseEffective =
        if (nearCompletion && stallCount == 0 && !allMissingUnresponsive)
          baseTimeout + (baseTimeout / 2)
        else baseTimeout

      phaseMultiplier = ops.phaseIndex(state.status) match {
        case 0 => config.facilitiesTimeoutMultiplier
        case 1 => config.proposalsTimeoutMultiplier
        case 2 => config.signaturesTimeoutMultiplier
        case _ => 1.0
      }
    } yield FiniteDuration((baseEffective.toMillis * phaseMultiplier).toLong, MILLISECONDS)

  // ── Stall Handling ────────────────────────────────────────────────

  /** Handle a stall condition. Returns true if a stall was detected.
    *
    * When peers are missing (haven't declared for the current phase), they are evicted from the facilitator set via
    * `performViewChangeWithEviction`. This allows the remaining peers to continue with a reduced quorum instead of being stuck.
    *
    * When all peers have declared but the phase hasn't advanced (e.g., leader hasn't proposed), a normal view change (leader rotation) is
    * performed for proposal phases, or the stall is counted toward abandonment for other phases.
    */
  /** Result of a stall check: whether a stall was detected and whether quorum is infeasible. */
  private case class StallResult(
    didStall: Boolean,
    quorumInfeasible: Boolean,
    activeFacilitators: Int = 0,
    quorumSize: Int = 0,
    clusterSize: Int = 0
  )

  private def handleStall(
    key: Key,
    state: ConsensusState[Key, Status, Outcome, Kind],
    declarationTimeout: FiniteDuration,
    statusDuration: FiniteDuration,
    declaredCount: Int,
    activeCount: Int,
    missingPeerIds: Set[String],
    missingPeers: Set[PeerId],
    stallCount: Int
  ): F[StallResult] =
    if (statusDuration >= declarationTimeout) {
      val statusName = state.status.getClass.getSimpleName.stripSuffix("$")
      val phaseLabel = Seq((Metrics.unsafeLabelName("phase"), statusName))

      if (missingPeers.nonEmpty) {
        val totalFacilitators = state.facilitators.value.size
        val remaining = totalFacilitators - missingPeers.size
        // Quorum based on Ready peers in cluster, not current round's facilitator count.
        // Only Ready peers can participate in consensus, so Observing/WaitingForReady peers
        // don't inflate the denominator. This prevents eviction cascades from shrinking
        // quorum each round until a 2-node fork becomes self-sustaining.
        clusterStorage.getResponsivePeers.map(_.count(_.state === NodeState.Ready)).flatMap { readyPeerCount =>
          val clusterSize = math.max(readyPeerCount + 1, totalFacilitators)
          val minQuorum = (clusterSize / 2) + 1
          val quorumInfeasible = remaining < minQuorum

          // Graduated response: first stall warns and waits, second stall evicts.
          // This gives slow peers (gossip delay, network jitter) an extra timeout window
          // before being removed, preventing premature eviction cascades.
          if (stallCount == 0) {
            // First timeout — warn only, give peers one more cycle to respond
            ConsensusLog.warn(
              logger,
              ConsensusLog.Stall,
              key.toString,
              selfRole(state),
              "event" -> "PEER_STALL_WARNING",
              "phase" -> statusName,
              "elapsed" -> s"${statusDuration.toSeconds}s",
              "timeout" -> s"${declarationTimeout.toSeconds}s",
              "progress" -> s"$declaredCount/$activeCount",
              "missing" -> missingPeers.size.toString,
              "missingPeers" -> ConsensusLog.pids(missingPeers),
              "view" -> state.viewNumber.toString,
              "action" -> "waiting one more cycle before eviction"
            ) >>
              Metrics[F].incrementCounter("dag_consensus_stall_warning") >>
              Metrics[F].incrementCounter("dag_consensus_stall_phase", phaseLabel) >>
              StallResult(didStall = true, quorumInfeasible = false).pure[F] // Count as stall but don't evict
          } else {
            // Second+ timeout — evict missing peers
            // Record local eviction votes for missing peers (scaffolding for future gossip-based deterministic eviction)
            missingPeers.toList.traverse_(target => evictionVoteTracker.voteToEvict(selfId, target)) >>
              ConsensusLog.warn(
                logger,
                ConsensusLog.Stall,
                key.toString,
                selfRole(state),
                "event" -> (if (quorumInfeasible) "QUORUM_INFEASIBLE_AFTER_EVICTION" else "PEER_EVICTION"),
                "phase" -> statusName,
                "elapsed" -> s"${statusDuration.toSeconds}s",
                "timeout" -> s"${declarationTimeout.toSeconds}s",
                "progress" -> s"$declaredCount/$activeCount",
                "evicted" -> missingPeers.size.toString,
                "remaining" -> remaining.toString,
                "minQuorum" -> minQuorum.toString,
                "quorumFeasible" -> (!quorumInfeasible).toString,
                "evictedPeers" -> ConsensusLog.pids(missingPeers),
                "view" -> state.viewNumber.toString,
                "stallCount" -> stallCount.toString
              ) >>
              Metrics[F].incrementCounter("dag_consensus_peer_eviction") >>
              Metrics[F].incrementCounter("dag_consensus_stall_phase", phaseLabel) >>
              // If quorum is infeasible after eviction, skip the view change (it can't help)
              // and propagate quorumInfeasible to the main loop for retriable abandon.
              viewChangeManager
                .performViewChangeWithEviction(key, state, missingPeers)
                .unlessA(quorumInfeasible)
                .as(
                  StallResult(
                    didStall = true,
                    quorumInfeasible = quorumInfeasible,
                    activeFacilitators = remaining,
                    quorumSize = minQuorum,
                    clusterSize = clusterSize
                  )
                )
          }
        }
      } else if (ops.isProposalPhase(state.status)) {
        // All declared but leader hasn't proposed → normal view change (leader rotation only)
        ConsensusLog.warn(
          logger,
          ConsensusLog.Stall,
          key.toString,
          selfRole(state),
          "event" -> "LEADER_STALL",
          "phase" -> statusName,
          "elapsed" -> s"${statusDuration.toSeconds}s",
          "timeout" -> s"${declarationTimeout.toSeconds}s",
          "progress" -> s"$declaredCount/$activeCount",
          "leader" -> ConsensusLog.pid(state.leader),
          "view" -> state.viewNumber.toString
        ) >>
          Metrics[F].incrementCounter("dag_consensus_view_change") >>
          Metrics[F].incrementCounter("dag_consensus_stall_phase", phaseLabel) >>
          viewChangeManager.performViewChange(key, state).as(StallResult(didStall = true, quorumInfeasible = false))
      } else {
        // All declared but phase hasn't advanced → count toward abandon
        ConsensusLog.warn(
          logger,
          ConsensusLog.Stall,
          key.toString,
          selfRole(state),
          "event" -> "STALL_DETECTED",
          "phase" -> statusName,
          "elapsed" -> s"${statusDuration.toSeconds}s",
          "timeout" -> s"${declarationTimeout.toSeconds}s",
          "progress" -> s"$declaredCount/$activeCount"
        ) >>
          Metrics[F].incrementCounter("dag_consensus_stall_detected") >>
          Metrics[F].incrementCounter("dag_consensus_stall_phase", phaseLabel) >>
          StallResult(didStall = true, quorumInfeasible = false).pure[F]
      }
    } else {
      StallResult(didStall = false, quorumInfeasible = false).pure[F]
    }

  // ── Resource Info ─────────────────────────────────────────────────

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
        val missing = active -- respondedPeers
        ResourcesInfo(
          hash = respondedPeers.hashCode(),
          declaredCount = respondedPeers.size,
          activeCount = active.size,
          missingPeerIds = missing.toList.map(_.value.value.take(8)).toSet,
          missingPeers = missing
        )
      case None =>
        ResourcesInfo(
          hash = resources.peerDeclarationsMap.keySet.hashCode(),
          declaredCount = resources.peerDeclarationsMap.size,
          activeCount = active.size,
          missingPeerIds = Set.empty,
          missingPeers = Set.empty
        )
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────

  private def isLeaderUnresponsive(leader: PeerId): F[Boolean] =
    if (leader == ctx.selfId)
      false.pure[F] // Local node is always responsive to itself
    else
      clusterStorage.getPeer(leader).map {
        case Some(peer) => peer.responsiveness === (Unresponsive: PeerResponsiveness)
        case None       => true
      }

  private def getCurrentDeclarationTimeout: F[FiniteDuration] =
    ctx.nodeStorage.isInJoiningGracePeriod.map { isInJoiningGracePeriod =>
      if (isInJoiningGracePeriod) config.timeTriggerInterval else config.declarationTimeout
    }

  // ── Logging ───────────────────────────────────────────────────────

  private def logSummary(
    key: Key,
    state: ConsensusState[Key, Status, Outcome, Kind],
    info: ResourcesInfo,
    statusDuration: FiniteDuration,
    roundElapsed: FiniteDuration,
    stallCount: Int,
    statusName: String
  ): F[Unit] =
    Async[F].pure {
      val withdrawnCount = state.withdrawnFacilitators.value.size
      val missingCount = info.missingPeers.size

      val summaryPairs = Seq(
        "event" -> "ROUND_MONITOR",
        "phase" -> statusName,
        "progress" -> s"${info.declaredCount}/${info.activeCount}",
        "facilitators" -> state.facilitators.value.size.toString,
        "phaseElapsed" -> s"${statusDuration.toSeconds}s",
        "roundElapsed" -> s"${roundElapsed.toSeconds}s",
        "stallCount" -> stallCount.toString,
        "leader" -> ConsensusLog.pid(state.leader)
      ) ++
        (if (state.viewNumber > 0) Seq("view" -> state.viewNumber.toString) else Seq.empty) ++
        (if (withdrawnCount > 0) Seq("withdrawn" -> withdrawnCount.toString) else Seq.empty) ++
        (if (missingCount > 0) Seq("missing" -> missingCount.toString) else Seq.empty)
      summaryPairs
    }.flatMap(ConsensusLog.info(logger, ConsensusLog.Stall, key.toString, selfRole(state), _: _*))

  private def logPeerQualityScores(key: Key, role: String): F[Unit] =
    peerQualityTracker.getQualityScores.flatMap { scores =>
      if (scores.nonEmpty) {
        val sorted = scores.toList.sortBy(-_._2)
        val total = sorted.size
        val healthy = sorted.count(_._2 >= 0.7)
        val degraded = sorted.count(s => s._2 >= 0.3 && s._2 < 0.7)
        val unhealthy = sorted.count(_._2 < 0.3)
        ConsensusLog.info(
          logger,
          ConsensusLog.Facilitator,
          key.toString,
          role,
          "event" -> "PEER_QUALITY",
          "summary" -> s"healthy=$healthy,degraded=$degraded,unhealthy=$unhealthy",
          "trackedPeers" -> total.toString
        )
      } else
        ConsensusLog
          .debug(logger, ConsensusLog.Facilitator, key.toString, role, "event" -> "PEER_QUALITY", "trackedPeers" -> "0")
    }
}
