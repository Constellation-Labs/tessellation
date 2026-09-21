package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.kernel.{Async, Ref}
import cats.effect.syntax.all._
import cats.syntax.all._
import cats.{Order, Show}

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event => LogEvent}
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.node.{NodeState, NodeStateTransition}
import io.constellationnetwork.schema.peer.PeerId

import eu.timepit.refined.auto._

/** Why a round was abandoned. Determines whether the abandonment counts toward recovery. */
sealed trait AbandonReason {

  /** Human-readable description for logging. */
  def message: String

  /** Metric label for counters. */
  def label: String

  /** If true, this abandonment does NOT count toward consecutive recovery threshold. The node should retry consensus without escalating to
    * recovery download.
    */
  def retriable: Boolean

  /** When the abandonment carries a (active, required) facilitator-count pair, expose it. The retriable path in `AbandonmentTracker` reads
    * this to classify the escalation cause (isolated vs quorum-impossible). Only `QuorumInfeasible` carries this signal today.
    */
  def quorumPair: Option[(Int, Int)] = None
}

object AbandonReason {

  /** Not enough peers to reach quorum -- wait for peers to come back.
    *
    * Invariant: `active < required`. Constructed only by `StallDetector` inside the `quorumInfeasible = coreRemaining < coreQuorum` branch
    * with `active = coreRemaining` and `required = coreQuorum` (alpha.91: Core-only gate; pre-alpha.91 the gate used the full-facilitator
    * set). The retriable handler in `AbandonmentTracker` relies on this invariant for its isolated-vs-quorum-impossible classification.
    * `clusterSize` carries the round-start full-facilitator count for observability only.
    */
  final case class QuorumInfeasible(active: Int, required: Int, clusterSize: Int) extends AbandonReason {
    def message: String =
      s"quorum infeasible (Core gate): $active active < $required required (clusterSize=$clusterSize)"
    def label: String = "quorum_infeasible"
    def retriable: Boolean = true
    override def quorumPair: Option[(Int, Int)] = Some((active, required))
  }

  /** This node is behind the network — peers are at a higher ordinal. */
  final case class Lagging(
    peersAhead: Int,
    totalPeers: Int,
    totalRegs: Int,
    followerCatchUpEligible: Boolean = false
  ) extends AbandonReason {
    def message: String = s"lagging behind network: $peersAhead/$totalPeers ready peers at higher key (totalRegs=$totalRegs)"
    def label: String = "lagging"
    def retriable: Boolean = false
  }

  /** Round exceeded maximum allowed duration. */
  final case class RoundTimeout(elapsedSeconds: Long, maxSeconds: Option[Long]) extends AbandonReason {
    def message: String = s"round timed out after ${elapsedSeconds}s (max=${maxSeconds}s)"
    def label: String = "timeout"
    def retriable: Boolean = false
  }

  /** Hit maximum stall cycles without resolution. */
  final case class MaxStalls(stallCount: Int) extends AbandonReason {
    def message: String = s"stuck after $stallCount stall cycles"
    def label: String = "max_stalls"
    def retriable: Boolean = false
  }

  /** Alpha.98 round-start participation feasibility: the round committee includes peers that are locally observed as NOT Ready AND whose
    * tip is at or before our `lastOutcome.key`, and excluding them drops the active count below quorum. Emitted purely as a local "I am not
    * going to burn cycles on a round that cannot make progress" guard -- the committee, the facilitator hash, the quorum derivation, and
    * the proposal validity rules are unchanged (no determinism implications). Retriable so the next time-trigger can re-evaluate with
    * possibly fresher peer-state observations; this is NOT counted as an eviction-grade signal and should not heavily penalize the missing
    * peers.
    */
  final case class ReadyParticipationQuorumInfeasible(
    activeReady: Int,
    required: Int,
    excludedCount: Int
  ) extends AbandonReason {
    def message: String =
      s"ready-participation quorum infeasible: $activeReady ready-and-current < $required required, " +
        s"excluding $excludedCount not-ready-or-behind peers"
    def label: String = "ready_participation_quorum_infeasible"
    def retriable: Boolean = true
    override def quorumPair: Option[(Int, Int)] = Some((activeReady, required))
  }

  implicit val show: Show[AbandonReason] = Show.show(_.message)
}

/** Tracks consecutive round abandonments and triggers recovery when stuck.
  *
  * ==Problem==
  *
  * When a node is desynchronized, it repeatedly attempts the same ordinal, fails (stall → abandon), and retries. Without intervention, this
  * infinite loop continues forever.
  *
  * ==Solution==
  *
  * Track consecutive abandonments at the same key. After `maxConsecutiveAbandonments`, transition the node to `WaitingForDownload` which
  * the DownloadDaemon picks up to fetch fresh state from peers.
  *
  * ==Extended Recovery Loop Protection==
  *
  * If the node enters a recovery loop (abandon → download → come back to same state → abandon → download → ...), a total recovery attempt
  * counter eventually forces the node to `Leaving` state. This breaks pathological loops where the downloaded state itself leads to the
  * same stuck ordinal. The hard limit is `maxConsecutiveAbandonments * 3` (default: 15 recovery attempts).
  *
  * ==Resource Cleanup==
  *
  * On every abandonment, stale peer declarations, artifacts, and withdrawal maps are cleared. Without this, abandoned rounds leave
  * resources that poison retries via `.orElse` semantics in `addFacility`.
  */
class AbandonmentTracker[F[_]: Async: Metrics, Event, Key: Order, Artifact, Ctx, Status, Outcome, Kind](
  ctx: ConsensusEngineContext[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind],
  healthRef: Ref[F, ConsensusHealthStatus],
  // Layer-supplied HTTP preflight for the rumor-stale escalation shape: does a corroborated group
  // of Ready peers report the same committed snapshot at or above the abandoned key? See
  // `AbandonmentTracker.EscalationSignal` for why frozen rumor state alone must never escalate,
  // and `PeersCommittedAheadProbe.make` for the standard implementation both layers wire in.
  peersCommittedAheadProbe: Key => F[AbandonmentTracker.PeersAheadProbe],
  // Layer-supplied transport/discovery capabilities for the B2' rehabilitation pass and the B1' isolation repair. The default leaves
  // every repair step inert (reported as not wired) so the engine behaves as before until a layer wires `IsolationRepair.Hooks.wired`.
  isolationHooks: IsolationRepair.Hooks[F] = IsolationRepair.Hooks.none[F]
) {

  import ctx.{clusterStorage, config, logger, peerQualityTracker, queue, storage}
  import AbandonmentTracker.{EscalationCause, EscalationSignal, Evidence, PeersAheadProbe, StaleKeyTelemetry, SuppressedBy}

  /** B3' probe scheduling coordinator, shared by both abandonment paths, the locked-attempt path and the B1' repair. Residence gate = one
    * time-trigger interval; completion-based cooldown = `config.abandonmentProbeCooldown`.
    */
  val probeCoordinator: ProbeCoordinator[F, Key] =
    ProbeCoordinator.unsafe[F, Key](config.timeTriggerInterval, config.abandonmentProbeCooldown)

  private val recheckLedger: IsolationRepair.PeerRecheckLedger[F] = IsolationRepair.PeerRecheckLedger.unsafe[F]
  private val repairGuard: IsolationRepair.RunGuard[F] = IsolationRepair.RunGuard.unsafe[F](config.isolationRepairCooldown)

  private val rehabilitationBudget: IsolationRepair.Budget = IsolationRepair.Budget(
    sampleSize = config.rehabilitationSampleSize,
    parallelism = IsolationRepair.Budget.Parallelism,
    perPeerTimeout = IsolationRepair.Budget.PerPeerTimeout,
    overallTimeout = IsolationRepair.Budget.OverallTimeout,
    peerCooldown = config.isolationRepairPeerCooldown,
    maxJitter = Duration.Zero
  )

  private val recheckBudget: IsolationRepair.Budget = rehabilitationBudget.copy(
    sampleSize = Int.MaxValue,
    parallelism = config.isolationRepairConcurrency,
    maxJitter = AbandonmentTracker.RepairRecheckMaxJitter
  )

  /** D1 stale-key rate limiter, shared with `StallDetector` (which feeds it per monitor tick and captures at the same-key suppression
    * boundary). See `AbandonmentTracker.StaleKeyTelemetry` for the reset and bounding contract.
    */
  val staleKeyTelemetry: StaleKeyTelemetry[F, Key] = StaleKeyTelemetry.unsafe[F, Key]

  /** D2 disposition counter. Telemetry only: a metrics failure can never reach a consensus decision. */
  private def recordDisposition(by: SuppressedBy): F[Unit] =
    Metrics[F]
      .incrementCounter("dag_consensus_recovery_suppressed_total", Seq(Metrics.unsafeLabelName("by") -> by.label))
      .attempt
      .void

  private def dispositionOfTransition(transitioned: Boolean): SuppressedBy =
    if (transitioned) SuppressedBy.Unsuppressed else SuppressedBy.StateTransitionFailed

  /** D1: emit the rate-limited stale-key WARN for `key` with the attempt/resource/phase/view snapshot and the three separate ages
    * (wall-clock parent age, monotonic local residence, monotonic time since the last observed external Facility). Called at the monitor's
    * same-key suppression boundary and before `performAbandon` clears state, so locked attempts are visible too. Every effect here is
    * `.attempt.void`: a logging or storage-read failure never changes a consensus decision.
    */
  def captureStaleKey(
    site: String,
    key: Key,
    requestedReason: String,
    state: ConsensusState[Key, Status, Outcome, Kind],
    extraPairs: (String, String)*
  ): F[Unit] =
    captureStaleKeyWith(site, key, requestedReason, state, force = false, extraPairs.toList)

  /** `force = true` bypasses the per-key reminder budget (used once per B1' repair run, which has its own cooldown). */
  private def captureStaleKeyWith(
    site: String,
    key: Key,
    requestedReason: String,
    state: ConsensusState[Key, Status, Outcome, Kind],
    force: Boolean,
    extraPairs: List[(String, String)]
  ): F[Unit] =
    staleKeyTelemetry
      .capture(key, ctx.lastOutcomeKeyOf(state.lastOutcome), force)
      .flatMap(_.traverse_ { emission =>
        for {
          attemptId <- storage.getRoundAttemptId
          resourceGeneration <- storage.getResourceGeneration(key)
          nowWallMs <- Async[F].realTime.map(_.toMillis)
          declarations <- storage.getPeerDeclarations(key)
          responsivePeers <- clusterStorage.getResponsivePeers
          retriable <- retriableAtSameKeyRef.get.map { case (lastKey, count) => if (lastKey.exists(_ === key)) count else 0 }
          consecutive <- consecutiveAbandonmentsFor(key)
          parentAgeMs = ctx.lastOutcomeEndTimeMsOf(state.lastOutcome).fold("unknown")(end => (nowWallMs - end).toString)
          pairs = List(
            "reason" -> "STALE_KEY",
            "site" -> site,
            "requestedReason" -> requestedReason,
            "warnKind" -> emission.kind,
            "warnsSoFar" -> emission.warnsSoFar.toString,
            "attemptId" -> attemptId.toString,
            "resourceGeneration" -> resourceGeneration.toString,
            "phase" -> state.status.getClass.getSimpleName.stripSuffix("$"),
            "phaseIndex" -> ctx.ops.phaseIndex(state.status).toString,
            "view" -> state.viewNumber.toString,
            "parentAgeMs" -> parentAgeMs,
            "residenceMs" -> emission.residence.toMillis.toString,
            "lastExternalFacilityAgoMs" -> emission.lastExternalFacilityAgo.fold("unknown")(_.toMillis.toString),
            "facilitiesReceived" -> declarations.count { case (_, decls) => decls.facility.isDefined }.toString,
            "committee" -> state.facilitators.value.size.toString,
            "responsiveReadyPeers" -> responsivePeers.count(_.state === NodeState.Ready).toString,
            "retriableAtSameKey" -> retriable.toString,
            "consecutiveAbandonments" -> consecutive.toString
          ) ++ extraPairs
          _ <- ConsensusLog.warn(
            logger,
            Category.Stall,
            key.toString,
            ConsensusLog.role(ctx.selfId, state.leader),
            LogEvent.StallDetected,
            pairs: _*
          )
        } yield ()
      })
      .attempt
      .void

  /** Emit a `RoundCompleted` tagged with the current attempt id so the FSM can drop it if the round has since advanced. See Bug A in the
    * fork-recovery post-mortem: an abandonment-queued `RoundCompleted` fired after a view change had moved the round forward and wiped the
    * nearly-finished round.
    */
  private def offerRoundCompleted: F[Unit] =
    storage.getRoundAttemptId.flatMap(id => queue.offer(ConsensusCommand.RoundCompleted(id)))

  private def retryAfterRetriableAbandon(key: Key, reason: AbandonReason): F[Unit] = {
    val shouldBackoff = reason match {
      case _: AbandonReason.ReadyParticipationQuorumInfeasible => true
      case _                                                   => false
    }
    val retryDelay = config.viewChangeApplyDelay / 2

    offerRoundCompleted >>
      (if (shouldBackoff)
         ConsensusLog.info(
           logger,
           Category.Lifecycle,
           key.toString,
           "n/a",
           LogEvent.RoundAbandonedTracked,
           "reason" -> reason.label,
           "action" -> "delayed_retriable_retry",
           "retryDelayMs" -> retryDelay.toMillis.toString
         ) >>
           Metrics[F].incrementCounter(
             "dag_consensus_retriable_retry_delayed_total",
             Seq(Metrics.unsafeLabelName("reason") -> reason.label)
           ) >>
           Async[F].start(Async[F].sleep(retryDelay) >> queue.offer(ConsensusCommand.TimeTick)).void
       else queue.offer(ConsensusCommand.TimeTick))
  }

  /** Tracks consecutive abandonments at the same key to detect infinite stuck loops. */
  private val consecutiveAbandonCountRef: Ref[F, (Option[Key], Int)] = Ref.unsafe((none[Key], 0))

  /** Tracks consecutive retriable abandonments at the same key. If the node is stuck at the same ordinal with quorum-infeasible for too
    * long (e.g., post-chaos where one node forked ahead), this escalates to non-retriable after `maxRetriableAtSameKey` attempts.
    *
    * Lives on `ConsensusEngineContext` so other components can observe the retry pattern. It must not be used as consensus-critical view
    * input: nodes can process abandonments at different rates, and seeding `viewNumber` from this local counter fragments VCV/VCC assembly.
    */
  private val retriableAtSameKeyRef: Ref[F, (Option[Key], Int)] = ctx.retriableAtSameKeyRef

  /** After this many retriable abandonments at the same ordinal, escalate to recovery. Default: 1x maxConsecutiveAbandonments (5 with
    * default config). This is higher than the non-retriable threshold because quorum-infeasible is expected during transient partitions.
    */
  private val maxRetriableAtSameKey: Int = config.maxConsecutiveAbandonments

  /** Tracks total recovery download attempts across all keys to detect extended recovery loops. */
  private val totalRecoveryAttemptsRef: Ref[F, Int] = Ref.unsafe(0)

  /** Reset recovery counters after a successful consensus round. This prevents a node that recovered successfully from carrying stale
    * recovery history that could trigger premature force-leave on a future (unrelated) recovery.
    */
  def resetOnSuccessfulRound: F[Unit] =
    totalRecoveryAttemptsRef.set(0) >>
      retriableAtSameKeyRef.set((none[Key], 0)) >>
      healthRef.update(_.copy(totalRecoveryAttempts = 0, wedgeDetectedAtMs = None)) >>
      staleKeyTelemetry.reset.attempt.void

  /** Threshold for declaring a "sustained wedge": retriable abandonments at the same key with no peer ahead. Set to half the recovery
    * threshold so the wedge signal fires before recovery would have been triggered if a peer WERE ahead. Read by Cluster.leave() guard.
    */
  private val wedgeRetriableThreshold: Int = math.max(2, config.maxConsecutiveAbandonments / 2)

  /** Update health snapshot fields visible to Cluster.leave() guard. Called from the retriable path after `peersAtHigherKey` is computed.
    * Sets `wedgeDetectedAtMs` once when sustained quorum-infeasible-without-peers-ahead is observed; preserves the timestamp across
    * subsequent abandonments at the same key so the time-based escape hatch in Cluster.leave() measures from first detection.
    */
  private def updateWedgeHealth(
    retriableCount: Int,
    peersAtHigherKey: Int,
    reasonLabel: String
  ): F[Unit] =
    Async[F].monotonic.flatMap { now =>
      healthRef.update { h =>
        val nextWedgeAt =
          if (retriableCount >= wedgeRetriableThreshold && peersAtHigherKey == 0)
            h.wedgeDetectedAtMs.orElse(Some(now.toMillis))
          else
            None
        h.copy(
          peersAtHigherKey = peersAtHigherKey,
          lastAbandonReason = Some(reasonLabel),
          wedgeDetectedAtMs = nextWedgeAt
        )
      }
    }

  /** Track a failed initFromDownload attempt. Called by the event loop error handler when InitializeFromDownload exhausts retries. Without
    * this, repeated init failures would loop forever (download → init fail → download) because the recovery counter is only incremented by
    * abandonRound, not by init failures. After maxTotalRecoveryAttempts, the node will force-leave the cluster.
    */
  def trackInitFromDownloadFailure: F[Unit] =
    totalRecoveryAttemptsRef.updateAndGet(_ + 1).flatMap { totalAttempts =>
      val shouldForceLeave = totalAttempts >= maxTotalRecoveryAttempts
      healthRef.update(_.copy(totalRecoveryAttempts = totalAttempts)) >>
        ConsensusLog.warn(
          logger,
          Category.Lifecycle,
          "n/a",
          "n/a",
          LogEvent.InitDownloadFailureTracked,
          "totalRecoveryAttempts" -> totalAttempts.toString,
          "maxTotalRecoveryAttempts" -> maxTotalRecoveryAttempts.toString,
          "willForceLeave" -> shouldForceLeave.toString
        ) >>
        Metrics[F].incrementCounter("dag_consensus_init_download_failure_tracked") >>
        (if (shouldForceLeave)
           ConsensusLog.error(
             logger,
             Category.Lifecycle,
             "n/a",
             "n/a",
             LogEvent.ForceLeaveFromInitFailures,
             "totalRecoveryAttempts" -> totalAttempts.toString,
             "reason" -> "repeated initFromDownload failures exhausted recovery attempts"
           ) >>
             Metrics[F].incrementCounter("dag_consensus_force_leave_triggered") >>
             forceLeaveFromInitFailures(totalAttempts)
         else Async[F].unit)
    }

  /** Force the node to leave the cluster after exhausting initFromDownload recovery attempts. Similar to forceLeave but doesn't require a
    * Key parameter since init failures don't have a round key context.
    */
  private def forceLeaveFromInitFailures(totalAttempts: Int): F[Unit] = {
    val forceLeaveStates = List(
      NodeState.Ready,
      NodeState.WaitingForDownload,
      NodeState.DownloadInProgress,
      NodeState.Observing
    )

    def tryStates(remaining: List[NodeState]): F[Boolean] =
      remaining match {
        case Nil => false.pure[F]
        case state :: rest =>
          ctx.nodeStorage.tryModifyStateGetResult(state, NodeState.Leaving).flatMap {
            case NodeStateTransition.Success => true.pure[F]
            case _                           => tryStates(rest)
          }
      }

    // Check if already in Leaving state first to avoid futile transition attempts
    ctx.nodeStorage.getNodeState.flatMap { currentState =>
      if (currentState === NodeState.Leaving) {
        ConsensusLog.warn(
          logger,
          Category.Lifecycle,
          "n/a",
          "n/a",
          LogEvent.ForceLeaveInitFailuresAlreadyLeaving,
          "totalRecoveryAttempts" -> totalAttempts.toString,
          "reason" -> "node already in Leaving state, cleaning up consensus and stopping"
        ) >>
          consecutiveAbandonCountRef.set((none[Key], 0)) >>
          totalRecoveryAttemptsRef.set(0) >>
          healthRef.update(_.copy(consecutiveAbandonments = 0, totalRecoveryAttempts = 0)) >>
          ctx.pending.clear() >>
          offerRoundCompleted
      } else {
        tryStates(forceLeaveStates).flatMap {
          case true =>
            ConsensusLog.error(
              logger,
              Category.Lifecycle,
              "n/a",
              "n/a",
              LogEvent.ForceLeaveInitFailuresSuccess,
              "totalRecoveryAttempts" -> totalAttempts.toString
            ) >>
              consecutiveAbandonCountRef.set((none[Key], 0)) >>
              totalRecoveryAttemptsRef.set(0) >>
              healthRef.update(_.copy(consecutiveAbandonments = 0, totalRecoveryAttempts = 0)) >>
              ctx.pending.clear() >>
              offerRoundCompleted
          case false =>
            ConsensusLog.warn(
              logger,
              Category.Lifecycle,
              "n/a",
              "n/a",
              LogEvent.ForceLeaveInitFailuresFailed,
              "reason" -> "could not transition to Leaving from any state"
            )
        }
      }
    }
  }

  /** Abandon a round: clear state, track consecutive failures, and either retry or trigger recovery. Quorum-infeasible abandonments are
    * retried without counting toward recovery threshold, since the node isn't stuck or forked — it just needs more peers to reach quorum.
    *
    * Bug A guard for the queued abandon (#1): `AbandonRound` is enqueued by the `StallDetector` monitor and drained later on the command
    * loop. The monitor only decides to abandon when no outcome is ready (see `StallDetector.monitorStep`); re-checking outcome-readiness
    * here closes the decision-to-drain gap so a round that completed in between is left intact for `ConsensusFinished` rather than wiped.
    * `expectedAttemptId` and `expectedResourceGeneration` close the remaining queue races: a certified view/phase advance or a newly
    * received declaration between the monitor's decision and command drain must not erase the newer attempt/evidence. The command is
    * deliberately skipped in that case and the event loop re-arms monitoring.
    */
  def abandonRound(
    key: Key,
    reason: AbandonReason,
    expectedAttemptId: Long,
    expectedResourceGeneration: Long
  ): F[Unit] =
    (storage.getRoundAttemptId, storage.getResourceGeneration(key)).tupled.flatMap {
      case (currentAttemptId, currentResourceGeneration)
          if !AbandonmentTracker.isCurrentDecision(
            expectedAttemptId,
            expectedResourceGeneration,
            currentAttemptId,
            currentResourceGeneration
          ) =>
        ConsensusLog.debug(
          logger,
          Category.Lifecycle,
          key.toString,
          "n/a",
          LogEvent.RoundAbandoned,
          "reason" -> reason.label,
          "skipped" -> "stale_attempt_or_resources",
          "expectedAttemptId" -> expectedAttemptId.toString,
          "currentAttemptId" -> currentAttemptId.toString,
          "expectedResourceGeneration" -> expectedResourceGeneration.toString,
          "currentResourceGeneration" -> currentResourceGeneration.toString
        ) >>
          Metrics[F].incrementCounter("dag_consensus_abandon_skipped_stale_attempt_or_resources_total")
      case _ =>
        (storage.getState(key), storage.getVoteLock(key)).tupled.flatMap {
          case (Some(state), _) if ctx.advancer.getConsensusOutcome(state).isDefined =>
            ConsensusLog.debug(
              logger,
              Category.Lifecycle,
              key.toString,
              "n/a",
              LogEvent.RoundAbandoned,
              "reason" -> reason.label,
              "skipped" -> "outcome_ready"
            ) >>
              Metrics[F].incrementCounter("dag_consensus_abandon_skipped_outcome_ready_total")
          case (Some(state), voteLock) =>
            val fromView = state.viewNumber.toLong
            val toView = fromView + 1L
            val lastSnapshotHash = ctx.lastSnapshotHashOf(state.lastOutcome)

            (
              storage.isAssembledVccApplyScheduled(key, lastSnapshotHash, fromView, toView),
              storage.isTimeoutCertificateApplyScheduled(key, lastSnapshotHash, fromView, toView)
            ).tupled.flatMap {
              case (vccScheduled, timeoutScheduled) if vccScheduled || timeoutScheduled =>
                // A quorum-certified transition is stronger than the monitor's earlier local abandon decision. Exact tuple checks prevent
                // an old-view marker from pinning a later attempt. Re-offering heals a delayed fiber lost to cancellation or an incidental
                // effect failure; apply remains idempotent under the storage latch.
                ConsensusLog.info(
                  logger,
                  Category.Lifecycle,
                  key.toString,
                  "n/a",
                  LogEvent.RoundAbandoned,
                  "reason" -> reason.label,
                  "skipped" -> "certified_view_apply_scheduled",
                  "fromView" -> fromView.toString,
                  "toView" -> toView.toString,
                  "vccScheduled" -> vccScheduled.toString,
                  "timeoutScheduled" -> timeoutScheduled.toString
                ) >>
                  Metrics[F].incrementCounter("dag_consensus_abandon_skipped_certified_view_total") >>
                  recordDisposition(SuppressedBy.ProtectedLockOrCertifiedTransition) >>
                  queue.offer(ConsensusCommand.CheckViewChangeApply(key, fromView, toView)).whenA(vccScheduled) >>
                  queue.offer(ConsensusCommand.CheckTimeoutCertificateApply(key, fromView, toView)).whenA(timeoutScheduled)
              case _
                  if StallDetector.sameKeyRestartUnsafe(
                    state.viewNumber,
                    ctx.ops.phaseIndex(state.status),
                    voteLock.exists(_.blocksLegacyViewChange),
                    storage.viewSafetyMode(state.certifiedConsensusActive)
                  ) =>
                // The monitor may have queued this command immediately before proposal
                // acceptance/self-signing. VoteLock writes do not necessarily bump the
                // round attempt id, so re-check the safety boundary at drain time as well.
                reason match {
                  case _: AbandonReason.Lagging =>
                    // The locked-attempt rule is unchanged: only a corroborated probe may release the lock. The
                    // evidence unit runs through the B3' coordinator (rehabilitation pass, then the probe whenever
                    // Ready peers exist); a suppressed unit reads as no evidence and retains the attempt.
                    clusterStorage.getResponsivePeers
                      .map(_.count(_.state === NodeState.Ready))
                      .flatMap(readyPeerCount => gatherEvidence(key, EscalationSignal.noRumor, readyPeerCount))
                      .flatMap { evidence =>
                        val probe = evidence.probe
                        val action = AbandonmentTracker.lockedAttemptAction(reason, probe)
                        val decisionSuppressedBy = action match {
                          case AbandonmentTracker.LockedAttemptAction.RecoverByDownload => SuppressedBy.Unsuppressed
                          case AbandonmentTracker.LockedAttemptAction.Retain            => evidence.suppressedBy(EscalationSignal.noRumor)
                        }
                        val observe = ConsensusLog.warn(
                          logger,
                          Category.Recovery,
                          key.toString,
                          "n/a",
                          LogEvent.RoundAbandoned,
                          List(
                            "reason" -> reason.label,
                            "action" -> action.label,
                            "suppressedBy" -> decisionSuppressedBy.label,
                            "view" -> state.viewNumber.toString,
                            "phaseIndex" -> ctx.ops.phaseIndex(state.status).toString,
                            "highestVotedView" -> voteLock.flatMap(_.highestVotedView).fold("none")(_.toString),
                            "lockedQcView" -> voteLock.flatMap(_.lockedQc).fold("none")(_.view.toString)
                          ) ++ evidence.logPairs: _*
                        ) >> Metrics[F].incrementCounter(
                          "dag_consensus_locked_lagging_recovery_probe_total",
                          Seq(
                            Metrics.unsafeLabelName("action") -> action.label,
                            Metrics.unsafeLabelName("outcome") -> probe.outcome.label
                          )
                        )

                        captureStaleKey("pre_abandon", key, reason.label, state, "action" -> action.label) >>
                          observe.attempt.void >> (action match {
                            case AbandonmentTracker.LockedAttemptAction.RecoverByDownload =>
                              attemptRecoveryDownload(
                                key,
                                reason.label,
                                "locked_lagging_corroborated",
                                retainRoundOnTransitionFailure = true,
                                preferFollowerCatchUp = AbandonmentTracker.followerCatchUpEligible(reason)
                              ).map(dispositionOfTransition)
                            case AbandonmentTracker.LockedAttemptAction.Retain => decisionSuppressedBy.pure[F]
                          }).flatMap(recordDisposition)
                      }
                  case _ =>
                    ConsensusLog.warn(
                      logger,
                      Category.Lifecycle,
                      key.toString,
                      "n/a",
                      LogEvent.RoundAbandoned,
                      "reason" -> reason.label,
                      "skipped" -> "same_key_restart_unsafe_at_drain",
                      "view" -> state.viewNumber.toString,
                      "phaseIndex" -> ctx.ops.phaseIndex(state.status).toString,
                      "highestVotedView" -> voteLock.flatMap(_.highestVotedView).fold("none")(_.toString),
                      "lockedQcView" -> voteLock.flatMap(_.lockedQc).fold("none")(_.view.toString)
                    ) >>
                      Metrics[F].incrementCounter("dag_consensus_abandon_skipped_same_key_lock_total") >>
                      captureStaleKey("pre_abandon", key, reason.label, state, "skipped" -> "same_key_restart_unsafe_at_drain") >>
                      recordDisposition(SuppressedBy.ProtectedLockOrCertifiedTransition)
                }
              case _ =>
                performAbandon(key, reason)
            }
          case _ =>
            performAbandon(key, reason)
        }
    }

  private def performAbandon(key: Key, reason: AbandonReason): F[Unit] =
    // D1: snapshot attempt/phase/view and the residence ages BEFORE cleanup wipes them (rate-limited per key).
    storage.getState(key).flatMap(_.traverse_(state => captureStaleKey("pre_abandon", key, reason.label, state))).attempt.void >>
      // Retriable abandons (QuorumInfeasible / ReadyParticipationQuorumInfeasible) are routine transient
      // churn -- the node is not stuck or forked, it just needs more peers; log them at DEBUG. Reserve a
      // single WARN for the non-retriable cases (MaxStalls / RoundTimeout / Lagging) that an operator
      // actually wants to see. The dag_consensus_round_abandoned counter (below) is unconditional.
      (if (reason.retriable)
         ConsensusLog.debug(logger, Category.Lifecycle, key.toString, "n/a", LogEvent.RoundAbandoned, "reason" -> reason.message)
       else ConsensusLog.warn(logger, Category.Lifecycle, key.toString, "n/a", LogEvent.RoundAbandoned, "reason" -> reason.message)) >>
      Metrics[F].incrementCounter("dag_consensus_round_abandoned") >>
      Metrics[F].incrementCounter("dag_consensus_stall_abandon_reason", Seq((Metrics.unsafeLabelName("reason"), reason.label))) >>
      storage
        .condModifyState[Unit](key) {
          case Some(state) =>
            val mode = storage.viewSafetyMode(state.certifiedConsensusActive)
            // Attribute the abandon to its leader so operators can tell whether a flaky community
            // peer is dragging the cluster down. Pair with dag_consensus_round_completed_total
            // (same `peer_id` label) for a per-leader success-rate query.
            Metrics[F].incrementCounter(
              "dag_consensus_round_abandoned_by_leader_total",
              Seq(
                Metrics.unsafeLabelName("peer_id") -> ConsensusLog.pid(state.leader),
                Metrics.unsafeLabelName("reason") -> reason.label
              )
            ) >>
              peerQualityTracker
                .recordRoundAbandoned(state.facilitators.value.toSet)
                // Cleanup runs before condModifyState commits the state removal. A cleanup failure therefore leaves the exact state and
                // activation mode intact for the serialized retry instead of re-deriving legacy mode from an already-empty slot.
                .flatTap(_ => storage.clearResourcesPreservingDeclarations(key, mode))
                .as((none[ConsensusState[Key, Status, Outcome, Kind]], ()).some)
          case _ =>
            none[(Option[ConsensusState[Key, Status, Outcome, Kind]], Unit)].pure[F]
        }
        .void >>
      (if (reason.retriable)
         trackRetriableAtSameKey(key).flatMap { retriableCount =>
           val shouldEscalate = retriableCount >= maxRetriableAtSameKey
           ConsensusLog.info(
             logger,
             Category.Lifecycle,
             key.toString,
             "n/a",
             LogEvent.RoundAbandonedRetriable,
             "reason" -> reason.label,
             "detail" -> reason.message,
             "retriableAtSameKey" -> retriableCount.toString,
             "maxRetriableAtSameKey" -> maxRetriableAtSameKey.toString
           ) >>
             (if (shouldEscalate)
                // Stuck at the same ordinal with QuorumInfeasible for too long. Per the
                // QuorumInfeasible invariant (`active < required`, see AbandonReason.scala),
                // every retriable abandonment past this threshold means peers cannot form
                // quorum at this ordinal -- either the node is isolated (active==1), the node
                // has fallen behind (peers advanced past this ordinal), or the whole cluster
                // is stuck at this ordinal (e.g. fresh post-deploy where every facilitator
                // simultaneously reboots and cannot meet quorum on the first round).
                //
                // Apply the same `peersAtHigherKey > 0` gate the
                // non-retriable path uses. Without it, a fresh deploy where all source nodes
                // reboot together cascades all of them into WaitingForDownload on the FIRST
                // failed round at the new ordinal -- and since no peer is ahead, every node
                // loops in `Discovered 0/1 selectable peers, waiting 1 minute` forever. The
                // alpha.58 deploy deadlocked at ord 3122551 with exactly this
                // shape: clusterSize=7, active=3, requiredQuorum=5, no peer ahead. Retain
                // the original semantics when a peer IS ahead (isolated / lagging cases) by
                // keeping the same recovery-download trigger; only the cluster-wide-stall
                // case is suppressed.
                reason.quorumPair
                  .liftTo[F](new IllegalStateException(s"Retriable AbandonReason without quorumPair: $reason"))
                  .flatMap {
                    case (activeFacilitators, requiredQuorum) =>
                      val isIsolated = activeFacilitators <= 1
                      val cause = if (isIsolated) EscalationCause.Isolated else EscalationCause.QuorumImpossible
                      retriableAtSameKeyRef.set((none[Key], 0)) >>
                        trackConsecutiveAbandonments(key).flatMap { consecutiveCount =>
                          for {
                            // Mirror the non-retriable path's network-advance probe so the
                            // same observation drives both escalation paths. `peerCurrentKeys`
                            // is the live per-peer tip (max seen via incoming keyed rumors);
                            // `readyPeerIds` filters to peers currently in Ready state because
                            // a non-Ready peer's reported tip can't be downloaded from.
                            inputs <- readEscalationInputs(key)
                            // Fast path: rumor tips above the key escalate directly. Every other
                            // shape (all-below, at-key, empty map) is ambiguous between isolation
                            // and a cluster-wide stall, so the evidence unit (B2' rehabilitation
                            // pass, then the preflight whenever HTTP-Ready peers exist) asks them
                            // for committed progress -- escalation requires a corroborated
                            // `(ordinal, hash)` at/above the key. A genuine cluster-wide stall
                            // cannot corroborate it because nobody committed it. The B3'
                            // coordinator bounds how often the unit runs; a suppressed unit is
                            // simply "no evidence this cycle". See AbandonmentTracker.EscalationSignal.
                            evidence <- gatherEvidence(key, inputs.signal, inputs.readyPeerIds.size)
                            probe = evidence.probe
                            escalate = inputs.signal.decide(probe.confirmedAhead)
                            effectiveCause = if (escalate && !inputs.signal.networkAdvanced) EscalationCause.RumorIsolated else cause
                            decisionSuppressedBy = AbandonmentTracker.suppressedBy(
                              thresholdMet = true,
                              readyPeerCount = evidence.readyPeers,
                              signal = inputs.signal,
                              probe = probe,
                              escalated = escalate,
                              scheduling = evidence.scheduling
                            )
                            _ <- ConsensusLog.info(
                              logger,
                              Category.Lifecycle,
                              key.toString,
                              "n/a",
                              LogEvent.RetriableEscalated,
                              List(
                                "reason" -> reason.label,
                                "activeFacilitators" -> activeFacilitators.toString,
                                "requiredQuorum" -> requiredQuorum.toString,
                                "escalationCause" -> effectiveCause.label,
                                "triggerRecovery" -> escalate.toString,
                                "recoverySuppressed" -> (!escalate).toString,
                                "suppressedBy" -> decisionSuppressedBy.label
                              ) ++ inputs.logPairs ++ evidence.logPairs: _*
                            )
                            _ <- healthRef.update(_.copy(consecutiveAbandonments = consecutiveCount))
                            // Update wedge signal for Cluster.leave() guard. Fires when retriable abandonments at the same key
                            // pile up AND no peer is ahead - the symptom of an orchestration-induced wedge where consensus
                            // can't close because the committee is structurally short of quorum. Clears when peersAtHigherKey > 0
                            // (cluster has advanced) or when a round closes (resetOnSuccessfulRound).
                            _ <- updateWedgeHealth(retriableCount, inputs.peersAtHigherKey, reason.label)
                            disposition <-
                              if (escalate)
                                triggerRecoveryDownload(key, consecutiveCount, reason.label, effectiveCause.label)
                                  .map(dispositionOfTransition)
                              else retryAfterRetriableAbandon(key, reason).as(decisionSuppressedBy)
                            _ <- recordDisposition(disposition)
                          } yield ()
                        }
                  }
              else
                // B3': below the retriable threshold the evidence unit may still run (residence- and
                // cooldown-bound) so the stale-key diagnostics show whether the cluster committed past
                // this key. The disposition stays `threshold`: no transition is possible here.
                gatherEarlyEvidence(key, reason) >>
                  retryAfterRetriableAbandon(key, reason) >> recordDisposition(SuppressedBy.Threshold))
         }
       else
         // Non-retriable path (MaxStalls / RoundTimeout). Historically this
         // escalated to recovery unconditionally after maxConsecutiveAbandonments. During fork-recovery
         // E2E, that produced a cascading-recovery deadlock: the 4 active peers all hit max stalls
         // on the same ordinal (view-change thrashing), each independently entered Observing, and
         // then competed to download a snapshot the cluster had not produced — only the one remaining
         // Ready peer could serve, and it had nothing to serve. Quorum permanently broken.
         //
         // Distinguish "this node is behind" from "the whole cluster is stuck": only escalate to
         // recovery when peers have actually advanced past this key (peersAtHigherKey > 0).
         // Otherwise this is a cluster-wide stall and a recovery cascade would deadlock with no
         // Ready peers to serve downloads. Keep retrying — when peers do advance, we'll detect
         // it on a subsequent abandonment.
         //
         // `peersAtHigherKey` is read from Ready peers' registered observation keys. Uses the same
         // signal StallDetector uses for lagging detection (see StallDetector.scala where
         // `peersAtHigherKey > totalRegisteredPeers / 2` triggers the Lagging AbandonReason).
         trackConsecutiveAbandonments(key).flatMap { consecutiveCount =>
           val shouldRecover = consecutiveCount >= config.maxConsecutiveAbandonments
           for {
             // `peerCurrentKeys` = live per-peer tip (max seen via incoming keyed rumors).
             // Supersedes the old `peerRegistrations` read which was a one-time join-ordinal
             // and left lagging nodes with peersAtHigherKey=0 forever (Bug B).
             inputs <- readEscalationInputs(key)
             // Same evidence + preflight composition as the retriable path. B3': the evidence unit is
             // eligible on residence/cooldown rather than only at the recovery threshold, so an early
             // probe can show corroborated progress in the diagnostics; the transition below still
             // requires `shouldRecover` (unchanged), so pre-threshold evidence never recovers.
             evidence <- gatherEvidence(key, inputs.signal, inputs.readyPeerIds.size)
             probe = evidence.probe
             willRecover = shouldRecover && inputs.signal.decide(probe.confirmedAhead)
             recoveryCause = if (willRecover && !inputs.signal.networkAdvanced) EscalationCause.RumorIsolated.label else "non_retriable"
             decisionSuppressedBy = AbandonmentTracker.suppressedBy(
               thresholdMet = shouldRecover,
               readyPeerCount = evidence.readyPeers,
               signal = inputs.signal,
               probe = probe,
               escalated = willRecover,
               scheduling = evidence.scheduling
             )
             _ <- healthRef.update(_.copy(consecutiveAbandonments = consecutiveCount))
             _ <- ConsensusLog.info(
               logger,
               Category.Lifecycle,
               key.toString,
               "n/a",
               LogEvent.RoundAbandonedTracked,
               List(
                 "reason" -> reason.label,
                 "consecutiveAbandonments" -> consecutiveCount.toString,
                 "maxConsecutiveAbandonments" -> config.maxConsecutiveAbandonments.toString,
                 "triggerRecovery" -> willRecover.toString,
                 "recoverySuppressed" -> (shouldRecover && !willRecover).toString,
                 "suppressedBy" -> decisionSuppressedBy.label
               ) ++ inputs.logPairs ++ evidence.logPairs: _*
             )
             disposition <-
               if (willRecover)
                 triggerRecoveryDownload(
                   key,
                   consecutiveCount,
                   reason.label,
                   recoveryCause,
                   preferFollowerCatchUp = AbandonmentTracker.followerCatchUpEligible(reason)
                 ).map(dispositionOfTransition)
               else (offerRoundCompleted >> queue.offer(ConsensusCommand.TimeTick)).as(decisionSuppressedBy)
             _ <- recordDisposition(disposition)
           } yield ()
         })

  /** `peerCurrentKeys` is the live per-peer tip (max seen via incoming keyed rumors); `readyPeerIds` filters to peers currently Ready
    * because a non-Ready peer's reported tip cannot be downloaded from.
    */
  private def readEscalationInputs(key: Key): F[AbandonmentTracker.EscalationInputs[Key]] =
    for {
      peerCurrentKeys <- storage.getPeerCurrentKeys
      responsivePeers <- clusterStorage.getResponsivePeers
      readyPeerIds = responsivePeers.filter(_.state === NodeState.Ready).map(_.id).toSet
      readyPeerRegs = peerCurrentKeys.view.filterKeys(readyPeerIds.contains).toMap
    } yield
      AbandonmentTracker.EscalationInputs(
        readyPeerIds = readyPeerIds,
        readyPeerRegs = readyPeerRegs,
        peersAtHigherKey = readyPeerRegs.count { case (_, peerKey) => peerKey > key },
        peersAtSameKey = readyPeerRegs.count { case (_, peerKey) => peerKey === key },
        signal = AbandonmentTracker.escalationSignal(key, readyPeerRegs.values)
      )

  /** The recovery-evidence unit, run under the B3' coordinator: the B2' rehabilitation pass over retained Unresponsive peers, then the
    * committed-ahead probe whenever the fast path has not fired and Ready HTTP peers exist (`probeRequired`, unchanged; it reads the Ready
    * count AFTER the pass so a just-rehabilitated peer takes part in this ordinary probe). The fast path is untouched: when the rumor
    * signal already says the network advanced, nothing runs. A coordinator suppression (`in_flight` / `cooldown`) or a late result
    * (parent/generation moved while the unit ran) yields no evidence; every failure is folded into a non-confirming probe. This function
    * never decides anything: `decide`, `shouldRecover` and the locked-attempt rule read `evidence.probe.confirmedAhead` exactly as they
    * read the raw probe before.
    */
  private[engine] def gatherEvidence(key: Key, signal: EscalationSignal, readyPeerCount: Int): F[Evidence] =
    if (signal.networkAdvanced) Evidence.fastPath(readyPeerCount).pure[F]
    else
      (for {
        residence <- staleKeyTelemetry.residenceOf(key).attempt.map(_.toOption.flatten)
        generation <- storage.getResourceGeneration(key)
        scope = ProbeCoordinator.Scope(key, generation)
        result <- probeCoordinator.run(scope, residence, storage.getResourceGeneration(key).map(_ == generation)) {
          for {
            rehabilitation <- IsolationRepair.Rehabilitation
              .run(clusterStorage, isolationHooks.checkSession, recheckLedger, Async[F].monotonic, rehabilitationBudget)
            _ <- logRehabilitation(key, rehabilitation).attempt.void
            responsivePeers <- clusterStorage.getResponsivePeers
            readyPeers = responsivePeers.count(_.state === NodeState.Ready)
            probe <-
              if (signal.probeRequired(readyPeers)) peersCommittedAheadProbe(key).handleError(_ => PeersAheadProbe.failed)
              else PeersAheadProbe.none.pure[F]
          } yield (rehabilitation, readyPeers, probe)
        }
      } yield
        result match {
          case ProbeCoordinator.Result.Completed((rehabilitation, readyPeers, probe)) =>
            Evidence(probe, rehabilitation, readyPeers, scheduling = None, schedulingDetail = "ran", stale = false)
          case ProbeCoordinator.Result.Stale((rehabilitation, readyPeers, _)) =>
            Evidence(
              PeersAheadProbe.none,
              rehabilitation,
              readyPeers,
              scheduling = SuppressedBy.ProbeError.some,
              "stale_scope",
              stale = true
            )
          case ProbeCoordinator.Result.Suppressed(suppression) =>
            Evidence(
              PeersAheadProbe.none,
              IsolationRepair.Rehabilitation.Result.notWired.copy(wired = isolationHooks.checkSession.isDefined),
              readyPeerCount,
              scheduling = suppression.suppressedBy.some,
              schedulingDetail = suppression.detail,
              stale = false
            )
        }).handleError(_ =>
        Evidence(PeersAheadProbe.failed, IsolationRepair.Rehabilitation.Result.failed, readyPeerCount, None, "error", stale = false)
      )

  /** Pre-threshold retriable abandonment: run the (cadence-bound) evidence unit for diagnostics only and log it when it ran. */
  private def gatherEarlyEvidence(key: Key, reason: AbandonReason): F[Unit] =
    readEscalationInputs(key).flatMap { inputs =>
      gatherEvidence(key, inputs.signal, inputs.readyPeerIds.size).flatMap { evidence =>
        ConsensusLog
          .info(
            logger,
            Category.Lifecycle,
            key.toString,
            "n/a",
            LogEvent.RoundAbandonedRetriable,
            List(
              "reason" -> reason.label,
              "action" -> "early_evidence",
              "triggerRecovery" -> "false",
              "suppressedBy" -> SuppressedBy.Threshold.label
            ) ++ inputs.logPairs ++ evidence.logPairs: _*
          )
          .whenA(evidence.ran)
      }
    }.attempt.void

  private def logRehabilitation(key: Key, result: IsolationRepair.Rehabilitation.Result): F[Unit] =
    (ConsensusLog.info(
      logger,
      Category.Recovery,
      key.toString,
      "n/a",
      LogEvent.StallDetected,
      ("reason" -> "PEER_REHABILITATION") :: result.logPairs: _*
    ) >>
      Metrics[F].incrementCounterBy(
        "dag_consensus_peer_rehabilitation_total",
        result.restored.toLong,
        Seq(Metrics.unsafeLabelName("result") -> "restored")
      ) >>
      Metrics[F].incrementCounterBy(
        "dag_consensus_peer_rehabilitation_total",
        result.sessionChanged.toLong,
        Seq(Metrics.unsafeLabelName("result") -> "session_changed")
      ) >>
      Metrics[F].incrementCounterBy(
        "dag_consensus_peer_rehabilitation_total",
        result.unreachable.toLong,
        Seq(Metrics.unsafeLabelName("result") -> "unreachable")
      )).whenA(result.wired && result.sampled > 0)

  /** B1' trigger, evaluated by `StallDetector` on every monitor tick (never from an abandonment path a protected lock can suppress).
    * `responsiveReadyPeers` excludes self; the rule counts self in. When it fires and no repair is in flight or cooling down, the repair
    * runs on its own fiber so the monitor loop is never blocked. Diagnostic only: repair never touches node state, consensus state, or the
    * recovery decision, so in a common partition every node repairs and none recovers.
    */
  def maybeRepairIsolation(key: Key, state: ConsensusState[Key, Status, Outcome, Kind], responsiveReadyPeers: Int): F[Unit] =
    (staleKeyTelemetry.residenceOf(key), staleKeyTelemetry.lastExternalFacilityAgo).tupled.flatMap {
      case (residence, facilityAgo) =>
        val coreSize = state.coreFacilitators.value.size
        val coreQuorum = math.max(1, QuorumPolicy.fromFraction(coreSize, config.quorumThresholdFraction))
        val trigger =
          IsolationRepair.Trigger.evaluate(residence, facilityAgo, config.timeTriggerInterval, responsiveReadyPeers, coreSize, coreQuorum)
        val startRepair: F[Unit] = Async[F].monotonic.flatMap(repairGuard.tryStart).flatMap {
          case Some(skip) =>
            Metrics[F].incrementCounter(
              "dag_consensus_isolation_repair_total",
              Seq(Metrics.unsafeLabelName("action") -> s"skipped_${skip.label}")
            )
          case None =>
            Metrics[F].incrementCounter("dag_consensus_isolation_repair_total", Seq(Metrics.unsafeLabelName("action") -> "started")) >>
              Async[F].start(runRepair(key, trigger).guarantee(Async[F].monotonic.flatMap(repairGuard.finish))).void
        }
        startRepair.whenA(trigger.fire)
    }.attempt.void

  /** One bounded repair run: (1) non-demoting recheck of retained Responsive peers, (2) re-discovery through the layer hook, (3) the
    * evidence unit (B2' rehabilitation pass and, if eligible under B3', the probe), (4) the D1 stale-key WARN with `repair=true`.
    */
  private def runRepair(key: Key, trigger: IsolationRepair.Trigger): F[Unit] =
    (for {
      _ <- repairGuard.countRun
      recheck <- IsolationRepair.Recheck.run(clusterStorage, isolationHooks.recheckPeer, recheckLedger, Async[F].monotonic, recheckBudget)
      rediscovery <- IsolationRepair.Rediscovery.run(isolationHooks.rediscover, rehabilitationBudget.overallTimeout)
      responsivePeers <- clusterStorage.getResponsivePeers
      readyPeers = responsivePeers.count(_.state === NodeState.Ready)
      evidence <- gatherEvidence(key, EscalationSignal.noRumor, readyPeers)
      pairs = List("repair" -> "true", "repairAction" -> "diagnostic_only_no_recovery") ++
        trigger.logPairs ++ recheck.logPairs ++ rediscovery.logPairs ++ evidence.logPairs
      state <- storage.getState(key)
      _ <- state.fold(
        ConsensusLog.warn(
          logger,
          Category.Stall,
          key.toString,
          "n/a",
          LogEvent.StallDetected,
          (("reason" -> "ISOLATION_REPAIR") :: ("site" -> "repair") :: ("note" -> "round moved on before the repair finished") :: pairs): _*
        )
      )(s => captureStaleKeyWith("repair", key, "ISOLATION_REPAIR", s, force = true, pairs))
      _ <- Metrics[F].incrementCounter("dag_consensus_isolation_repair_total", Seq(Metrics.unsafeLabelName("action") -> "completed"))
    } yield ()).attempt.void

  /** Track consecutive abandonments at the same key. Returns the new count. Resets to 1 when the key changes (different ordinal).
    */
  private def trackConsecutiveAbandonments(key: Key): F[Int] =
    consecutiveAbandonCountRef.modify {
      case (Some(lastKey), count) if lastKey === key =>
        val newCount = count + 1
        ((key.some, newCount), newCount)
      case _ =>
        ((key.some, 1), 1)
    }

  /** Read-only accessor for `StallDetector` (v22): how many consecutive times has THIS key been abandoned? Returns 0 if the last-abandoned
    * key was a different ordinal (a successful round since then would also leave the tracked key behind, in which case 0 is the right
    * answer). Used to drive the defensive force-VCV short-circuit in `StallDetector.handleStall` without giving the caller mutate access to
    * the internal counter.
    */
  def consecutiveAbandonmentsFor(key: Key): F[Int] =
    consecutiveAbandonCountRef.get.map {
      case (Some(lastKey), count) if lastKey === key => count
      case _                                         => 0
    }

  /** Track retriable abandonments at the same key. If the node keeps getting quorum-infeasible at the same ordinal, something is
    * permanently wrong (e.g., post-partition with a 1-ordinal minority fork). Resets to 1 when the key changes.
    */
  private def trackRetriableAtSameKey(key: Key): F[Int] =
    retriableAtSameKeyRef.modify {
      case (Some(lastKey), count) if lastKey === key =>
        val newCount = count + 1
        ((key.some, newCount), newCount)
      case _ =>
        ((key.some, 1), 1)
    }

  /** Hard limit for total recovery attempts before forcing the node to leave the cluster. Default: 3 * maxConsecutiveAbandonments (e.g., 15
    * if maxConsecutiveAbandonments=5).
    */
  private val maxTotalRecoveryAttempts: Int = config.maxConsecutiveAbandonments * 3

  private def triggerRecoveryDownload(
    key: Key,
    consecutiveCount: Int,
    triggerReason: String,
    triggerClass: String,
    preferFollowerCatchUp: Boolean = false
  ): F[Boolean] =
    totalRecoveryAttemptsRef.updateAndGet(_ + 1).flatMap { totalAttempts =>
      val shouldForceLeave = totalAttempts >= maxTotalRecoveryAttempts

      (if (shouldForceLeave)
         ConsensusLog.error(
           logger,
           Category.Lifecycle,
           key.toString,
           "n/a",
           LogEvent.ForceLeaveTriggered,
           "trigger" -> triggerReason,
           "triggerClass" -> triggerClass,
           "consecutiveAbandonments" -> consecutiveCount.toString,
           "totalRecoveryAttempts" -> totalAttempts.toString,
           "maxTotalRecoveryAttempts" -> maxTotalRecoveryAttempts.toString,
           "reason" -> s"extended recovery loop: $totalAttempts recovery attempts exhausted, forcing node to leave"
         )
       else
         ConsensusLog.error(
           logger,
           Category.Lifecycle,
           key.toString,
           "n/a",
           LogEvent.RecoveryDownloadTriggered,
           "trigger" -> triggerReason,
           "triggerClass" -> triggerClass,
           "consecutiveAbandonments" -> consecutiveCount.toString,
           "totalRecoveryAttempts" -> totalAttempts.toString,
           "maxTotalRecoveryAttempts" -> maxTotalRecoveryAttempts.toString,
           "reason" -> s"stuck at same ordinal for $consecutiveCount consecutive rounds"
         )) >>
        healthRef.update(_.copy(totalRecoveryAttempts = totalAttempts)) >>
        Metrics[F].incrementCounter("dag_consensus_recovery_download_triggered") >>
        Metrics[F].incrementCounter(
          "dag_consensus_recovery_trigger_total",
          Seq(
            Metrics.unsafeLabelName("trigger") -> triggerReason,
            Metrics.unsafeLabelName("trigger_class") -> triggerClass,
            Metrics.unsafeLabelName("action") -> (if (shouldForceLeave) "force_leave"
                                                  else if (preferFollowerCatchUp) "follower_catch_up"
                                                  else "waiting_for_download")
          )
        ) >>
        (if (shouldForceLeave)
           Metrics[F].incrementCounter("dag_consensus_force_leave_triggered") >>
             forceLeave(key, totalAttempts)
         else
           attemptRecoveryDownload(
             key,
             triggerReason,
             triggerClass,
             preferFollowerCatchUp = preferFollowerCatchUp
           ))
    }

  /** Force the node to leave the cluster after exhausting all recovery attempts. This breaks pathological loops where downloaded state
    * leads to the same stuck ordinal. Tries multiple source states since the node could be in Ready, WaitingForDownload,
    * DownloadInProgress, or Observing when force-leave fires.
    */
  private def forceLeave(key: Key, totalAttempts: Int): F[Boolean] = {
    val forceLeaveStates = List(
      NodeState.Ready,
      NodeState.WaitingForDownload,
      NodeState.DownloadInProgress,
      NodeState.Observing
    )

    def tryStates(remaining: List[NodeState]): F[Boolean] =
      remaining match {
        case Nil => false.pure[F]
        case state :: rest =>
          ctx.nodeStorage.tryModifyStateGetResult(state, NodeState.Leaving).flatMap {
            case NodeStateTransition.Success => true.pure[F]
            case _                           => tryStates(rest)
          }
      }

    // First check if already in Leaving state — if so, no transition needed, just clean up and stop.
    // This prevents the infinite loop where forceLeave fails (already Leaving) → falls back to
    // attemptRecoveryDownload → also fails (not Ready/Observing) → queues TimeTick → repeat.
    ctx.nodeStorage.getNodeState.flatMap { currentState =>
      if (currentState === NodeState.Leaving) {
        ConsensusLog.warn(
          logger,
          Category.Lifecycle,
          key.toString,
          "n/a",
          LogEvent.ForceLeaveAlreadyLeaving,
          "totalRecoveryAttempts" -> totalAttempts.toString,
          "reason" -> "node already in Leaving state, cleaning up consensus and stopping"
        ) >>
          consecutiveAbandonCountRef.set((none[Key], 0)) >>
          totalRecoveryAttemptsRef.set(0) >>
          healthRef.update(_.copy(consecutiveAbandonments = 0, totalRecoveryAttempts = 0)) >>
          ctx.pending.clear() >>
          offerRoundCompleted.as(true)
      } else {
        tryStates(forceLeaveStates).flatMap {
          case true =>
            ConsensusLog.error(
              logger,
              Category.Lifecycle,
              key.toString,
              "n/a",
              LogEvent.ForceLeaveSuccess,
              "totalRecoveryAttempts" -> totalAttempts.toString,
              "reason" -> "node leaving cluster after extended recovery loop"
            ) >>
              consecutiveAbandonCountRef.set((none[Key], 0)) >>
              totalRecoveryAttemptsRef.set(0) >>
              healthRef.update(_.copy(consecutiveAbandonments = 0, totalRecoveryAttempts = 0)) >>
              ctx.pending.clear() >>
              offerRoundCompleted.as(true)
          case false =>
            // If we can't transition to Leaving from any state, fall back to recovery download
            ConsensusLog.warn(
              logger,
              Category.Lifecycle,
              key.toString,
              "n/a",
              LogEvent.ForceLeaveFailed,
              "reason" -> "could not transition to Leaving from any state, falling back to recovery download"
            ) >>
              attemptRecoveryDownload(key, "force_leave_failed", "force_leave_fallback")
        }
      }
    }
  }

  private def attemptRecoveryDownload(
    key: Key,
    triggerReason: String,
    triggerClass: String,
    retainRoundOnTransitionFailure: Boolean = false,
    preferFollowerCatchUp: Boolean = false
  ): F[Boolean] = {
    val recoveryStates = List(
      NodeState.Ready,
      NodeState.Observing,
      NodeState.WaitingForReady
    )

    def tryStates(remaining: List[NodeState]): F[Option[NodeState]] =
      remaining match {
        case Nil => none[NodeState].pure[F]
        case state :: rest =>
          ctx.nodeStorage.tryModifyStateGetResult(state, NodeState.WaitingForDownload).flatMap {
            case NodeStateTransition.Success => state.some.pure[F]
            case _                           => tryStates(rest)
          }
      }

    // Signal that this download is a recovery (not a fresh join).
    // DownloadDaemon will use the incremental recoveryDownload path.
    (if (preferFollowerCatchUp) ctx.nodeStorage.setFollowerCatchUpDownload else ctx.nodeStorage.setRecoveryDownload) >>
      tryStates(recoveryStates).flatMap {
        case Some(fromState) =>
          val downloadMode = if (preferFollowerCatchUp) "follower_catch_up" else "recovery"
          val observe = ConsensusLog.info(
            logger,
            Category.Lifecycle,
            key.toString,
            "n/a",
            LogEvent.RecoveryStateTransition,
            "trigger" -> triggerReason,
            "triggerClass" -> triggerClass,
            "downloadMode" -> downloadMode,
            "from" -> fromState.toString,
            "to" -> "WaitingForDownload"
          ) >>
            Metrics[F].incrementCounter(
              "dag_consensus_recovery_state_transition_total",
              Seq(
                Metrics.unsafeLabelName("trigger") -> triggerReason,
                Metrics.unsafeLabelName("trigger_class") -> triggerClass,
                Metrics.unsafeLabelName("outcome") -> "transitioned"
              )
            ) >> Metrics[F]
              .incrementCounter("dag_consensus_follower_catch_up_requested_total")
              .whenA(preferFollowerCatchUp)

          observe.attempt.void >>
            consecutiveAbandonCountRef.set((none[Key], 0)) >>
            healthRef.update(_.copy(consecutiveAbandonments = 0)) >>
            // Clear ALL consensus state (states, resources, peer registrations, scheduling state)
            // to ensure no stale data from previous abandoned rounds persists into post-recovery.
            // Without clearAllConsensusState, ghost entries from other ordinals can interfere
            // with the first post-recovery round. clearAllPeerRegistrations prevents false
            // lagging detection from stale departed-peer entries.
            // clearTimeTrigger and clearObservationKey prevent stale scheduling and observation
            // state from carrying over into the fresh context after download.
            storage.clearAllConsensusState >>
            storage.clearAllPeerRegistrations >>
            storage.clearTimeTrigger >>
            storage.clearObservationKey >>
            ctx.pending.clear() >>
            offerRoundCompleted.as(true)
        case None =>
          // Check if node is already in Leaving state — if so, just complete the round and stop.
          // CRITICAL: Do NOT queue TimeTick here. The old code queued RoundCompleted + TimeTick,
          // which created an infinite tight loop when node is in Leaving state:
          //   TimeTick → startRound → abandon → forceLeave(fails) → recoveryDownload(fails) → TimeTick → ...
          // By only queuing RoundCompleted (no TimeTick), the loop terminates after this iteration.
          // The next round will only start when an external trigger arrives (peer event, timer, etc.)
          ctx.nodeStorage.getNodeState.flatMap { currentState =>
            val observe = ConsensusLog.warn(
              logger,
              Category.Lifecycle,
              key.toString,
              "n/a",
              LogEvent.RecoveryTransitionFailed,
              "trigger" -> triggerReason,
              "triggerClass" -> triggerClass,
              "reason" -> s"node in $currentState state, not Ready or Observing",
              "nodeState" -> currentState.show
            ) >>
              Metrics[F].incrementCounter(
                "dag_consensus_recovery_state_transition_total",
                Seq(
                  Metrics.unsafeLabelName("trigger") -> triggerReason,
                  Metrics.unsafeLabelName("trigger_class") -> triggerClass,
                  Metrics.unsafeLabelName("outcome") -> "invalid_state"
                )
              )

            ctx.nodeStorage.clearRecoveryDownload.attempt.void.whenA(retainRoundOnTransitionFailure) >>
              observe.attempt.void >>
              (if (retainRoundOnTransitionFailure) Async[F].unit
               else ctx.pending.clear() >> offerRoundCompleted).as(false)
          }
      }
  }
}

object AbandonmentTracker {

  /** Rumor-side inputs of an escalation decision, read once per abandonment and shared by both paths. */
  private[engine] final case class EscalationInputs[Key](
    readyPeerIds: Set[PeerId],
    readyPeerRegs: Map[PeerId, Key],
    peersAtHigherKey: Int,
    peersAtSameKey: Int,
    signal: EscalationSignal
  ) {

    def logPairs: List[(String, String)] =
      List(
        "peersAtHigherKey" -> peersAtHigherKey.toString,
        "peersAtSameKey" -> peersAtSameKey.toString,
        "rumorStale" -> signal.rumorStale.toString,
        "readyPeers" -> readyPeerIds.size.toString,
        "registeredReadyPeers" -> readyPeerRegs.size.toString
      )
  }

  /** Upper bound of the per-peer start jitter in the B1' recheck. */
  val RepairRecheckMaxJitter: FiniteDuration = 500.millis

  /** Outcome of one evidence unit (see `gatherEvidence`). `probe` is what the decision rules read; everything else is telemetry. */
  final case class Evidence(
    probe: PeersAheadProbe,
    rehabilitation: IsolationRepair.Rehabilitation.Result,
    // Ready responsive peers after the rehabilitation pass (the pre-pass count when the unit did not run).
    readyPeers: Int,
    scheduling: Option[SuppressedBy],
    schedulingDetail: String,
    stale: Boolean
  ) {
    def ran: Boolean = scheduling.isEmpty

    /** Probe-side explanation when no rumor threshold applies (locked-attempt path). */
    def suppressedBy(signal: EscalationSignal): SuppressedBy =
      if (!signal.probeRequired(readyPeers)) SuppressedBy.NoCandidates
      else scheduling.getOrElse(probeSuppressedBy(probe))

    def logPairs: List[(String, String)] =
      probe.logPairs ++ rehabilitation.logPairs ++ List(
        "probeScheduling" -> schedulingDetail,
        "probeStale" -> stale.toString,
        "readyPeersAfterRehab" -> readyPeers.toString
      )
  }

  object Evidence {
    def fastPath(readyPeers: Int): Evidence =
      Evidence(PeersAheadProbe.none, IsolationRepair.Rehabilitation.Result.notWired, readyPeers, None, "fast_path", stale = false)
  }

  /** Only a node outside the frozen round committee may fast-forward through a committed successor. Committee members remain responsible
    * for that round and must use the full recovery boundary instead of silently skipping their voting obligation.
    */
  private[consensus] def followerCatchUpEligible(reason: AbandonReason): Boolean =
    reason match {
      case lagging: AbandonReason.Lagging => lagging.followerCatchUpEligible
      case _                              => false
    }

  private[consensus] sealed abstract class LockedAttemptAction(val label: String)
  private[consensus] object LockedAttemptAction {
    case object Retain extends LockedAttemptAction("retain_locked_attempt")
    case object RecoverByDownload extends LockedAttemptAction("corroborated_recovery_download")
  }

  /** A legacy GL0 vote lock may be cleared only by a real recovery/download boundary. Lagging is therefore not an ordinary-abandon bypass:
    * it retains the exact attempt unless the authenticated committed-snapshot probe corroborates a downloadable value at or beyond this
    * key. Other abandon reasons always retain the locked attempt.
    */
  private[consensus] def lockedAttemptAction(reason: AbandonReason, probe: PeersAheadProbe): LockedAttemptAction =
    reason match {
      case _: AbandonReason.Lagging if probe.confirmedAhead => LockedAttemptAction.RecoverByDownload
      case _                                                => LockedAttemptAction.Retain
    }

  /** Both epochs must still match at command drain. State and resource changes are independent: a fresh declaration can make an abandon
    * obsolete without advancing the consensus phase yet.
    */
  private[consensus] def isCurrentDecision(
    expectedAttemptId: Long,
    expectedResourceGeneration: Long,
    currentAttemptId: Long,
    currentResourceGeneration: Long
  ): Boolean =
    expectedAttemptId == currentAttemptId && expectedResourceGeneration == currentResourceGeneration

  /** Why a retriable abandonment escalated to recovery download. Used as a metric/log label. */
  private[engine] sealed abstract class EscalationCause(val label: String)
  private[engine] object EscalationCause {
    case object Isolated extends EscalationCause("isolated")
    case object QuorumImpossible extends EscalationCause("quorum_impossible")
    // Rumor-isolated escalation: the gossip view froze behind the abandoned key while HTTP-Ready
    // peers still exist, AND the HTTP preflight corroborated a committed snapshot at the abandoned
    // ordinal or newer (see `escalationSignal` + `PeersCommittedAheadProbe`). Distinct from
    // `Isolated`, which means the round itself saw activeFacilitators <= 1.
    case object RumorIsolated extends EscalationCause("rumor_isolated")
  }

  /** Result of the HTTP preflight (`PeersCommittedAheadProbe`): did a corroborated group of Ready peers report the same committed snapshot
    * identity at or above the abandoned key? Counts and outcome are retained for decision logs. Every non-completed outcome means NOT
    * confirmed, so degraded probes suppress recovery rather than trigger it.
    */
  final case class PeersAheadProbe(
    confirmedAhead: Boolean,
    probedPeers: Int,
    respondedPeers: Int,
    corroboratingPeers: Int,
    outcome: ProbeOutcome,
    // Reason data (telemetry only; the decision reads `confirmedAhead` alone). Per-peer local
    // results of the sampled fetches, the corroboration floor that applied, the number of distinct
    // `(ordinal, hash)` groups at/above the key, the ordinal the largest group agreed on, and how
    // many distinct hashes responders reported AT that ordinal -- so "same ordinal, different
    // hashes" (disagreement) is distinguishable from "not enough corroborators".
    timedOutPeers: Int = 0,
    erroredPeers: Int = 0,
    belowKeyPeers: Int = 0,
    atKeyPeers: Int = 0,
    aboveKeyPeers: Int = 0,
    requiredCorroborators: Int = 0,
    aheadGroups: Int = 0,
    corroboratingOrdinal: Option[Long] = None,
    hashesAtCorroboratingOrdinal: Int = 0
  ) {

    /** Log pairs shared by every decision line that reports a probe. Never used as metric labels. */
    def logPairs: List[(String, String)] =
      List(
        "probeConfirmedAhead" -> confirmedAhead.toString,
        "probeOutcome" -> outcome.label,
        "probeResponded" -> s"$respondedPeers/$probedPeers",
        "probeCorroborators" -> corroboratingPeers.toString,
        "probeTimedOut" -> timedOutPeers.toString,
        "probeErrored" -> erroredPeers.toString,
        "probeBelowKey" -> belowKeyPeers.toString,
        "probeAtKey" -> atKeyPeers.toString,
        "probeAboveKey" -> aboveKeyPeers.toString,
        "probeRequiredCorroborators" -> requiredCorroborators.toString,
        "probeAheadGroups" -> aheadGroups.toString,
        "probeCorroboratingOrdinal" -> corroboratingOrdinal.fold("none")(_.toString),
        "probeHashesAtCorroboratingOrdinal" -> hashesAtCorroboratingOrdinal.toString
      )
  }

  sealed abstract class ProbeOutcome(val label: String)
  object ProbeOutcome {
    case object NotRun extends ProbeOutcome("not_run")
    case object Completed extends ProbeOutcome("completed")
    case object TimedOut extends ProbeOutcome("timed_out")
    case object Failed extends ProbeOutcome("failed")
  }

  object PeersAheadProbe {
    val none: PeersAheadProbe = PeersAheadProbe(false, 0, 0, 0, ProbeOutcome.NotRun)
    val timedOut: PeersAheadProbe = PeersAheadProbe(false, 0, 0, 0, ProbeOutcome.TimedOut)
    val failed: PeersAheadProbe = PeersAheadProbe(false, 0, 0, 0, ProbeOutcome.Failed)
  }

  /** Recovery-escalation EVIDENCE for an abandoned key, from the rumor-registered tips of the HTTP-responsive Ready peers.
    *
    *   - `networkAdvanced`: some Ready peer's registered tip is ABOVE the abandoned key -- the cluster has provably moved on. Escalates on
    *     its own with no probe (the pre-existing fast path).
    *   - `rumorStale`: registrations EXIST but every one of them is STRICTLY BELOW the abandoned key -- the classic frozen-mesh signature
    *     (issue #1533; first fixed in `8027c0642`, dropped in the #1523 conflict resolution). DIAGNOSTIC ONLY: it labels the shape in the
    *     decision logs but carries no decision weight.
    *
    * Rumor state proves nothing beyond the fast path. `ConsensusStorage.observePeerAtKey` is monotone-max with no freshness and fed only by
    * incoming keyed rumors, and `clearAllPeerRegistrations` wipes the map during recovery -- so an isolated-but-HTTP-reachable node can sit
    * with the map frozen BELOW the key, frozen AT it (a single pre-isolation declaration for this key pins the entry forever), or EMPTY
    * (isolated after a recovery wipe, before any new rumor). All three shapes are byte-identical to a cluster that stalled together, where
    * escalation would cascade every node into WaitingForDownload with nobody able to serve (the historical false-lagging cascade recorded
    * in StallDetector's lagging-detection comment; the alpha.58 ord-3122551 deadlock).
    *
    * The discrimination therefore lives entirely in the HTTP preflight (`PeersCommittedAheadProbe`): whenever the fast path has not fired
    * and HTTP-Ready peers exist (`probeRequired`), ask a peer sample for their latest committed snapshot metadata. `decide` escalates iff
    * the fast path fired or the probe found a strict responder-majority agreeing on the same `(ordinal, hash)` at or above the abandoned
    * key -- at least two matching peers on any cluster large enough to provide two, clamped to the sample size so a two-node metagraph's
    * single peer can still confirm. A genuine cluster-wide stall is suppressed because nobody can corroborate a committed snapshot at the
    * key, rather than by guessing from frozen rumor shapes, which is exactly how the previous two attempts at this fix went wrong.
    *
    * Peer identity is irrelevant to the classification, so this takes only the key values.
    */
  final case class EscalationSignal(networkAdvanced: Boolean, rumorStale: Boolean) {

    /** Should the HTTP preflight run? Whenever the fast path has not fired and there is a Ready peer to ask: the probe is the
      * discriminator, so every non-advanced shape (all-below, at-key, empty map) gets one. With zero Ready HTTP peers there is nothing to
      * ask (and nothing to download from), so skip.
      */
    def probeRequired(readyPeerCount: Int): Boolean = !networkAdvanced && readyPeerCount > 0

    /** Final escalation decision given the preflight outcome. Pass `false` when the probe was not run, failed, or timed out -- degraded
      * probes must suppress, never trigger.
      */
    def decide(probeConfirmedAhead: Boolean): Boolean =
      networkAdvanced || probeConfirmedAhead
  }

  object EscalationSignal {

    /** No rumor signal computed (locked-attempt path and repair): the fast path never fires and the probe is required iff Ready peers
      * exist.
      */
    val noRumor: EscalationSignal = EscalationSignal(networkAdvanced = false, rumorStale = false)
  }

  def escalationSignal[K: Order](abandonedKey: K, readyPeerKeys: Iterable[K]): EscalationSignal = {
    val higher = readyPeerKeys.count(Order[K].gt(_, abandonedKey))
    val same = readyPeerKeys.count(Order[K].eqv(_, abandonedKey))
    EscalationSignal(
      networkAdvanced = higher > 0,
      rumorStale = readyPeerKeys.nonEmpty && higher == 0 && same == 0
    )
  }

  /** Why an abandonment did not become a recovery transition (D2). Bounded enum used as the `by` label of
    * `dag_consensus_recovery_suppressed_total`; `none` is the unsuppressed disposition. `in_flight` and `cooldown` come from the B3'
    * probe-scheduling coordinator (`cooldown` also covers the residence gate; the log line's `probeScheduling` pair distinguishes them).
    */
  sealed abstract class SuppressedBy(val label: String)
  object SuppressedBy {
    case object Threshold extends SuppressedBy("threshold")
    case object NoCandidates extends SuppressedBy("no_candidates")
    case object InFlight extends SuppressedBy("in_flight")
    case object Cooldown extends SuppressedBy("cooldown")
    case object ProbeTimeout extends SuppressedBy("probe_timeout")
    case object ProbeError extends SuppressedBy("probe_error")
    case object NoResponders extends SuppressedBy("no_responders")
    case object RespondersBelowKey extends SuppressedBy("responders_below_key")
    case object InsufficientCorroborators extends SuppressedBy("insufficient_corroborators")
    case object Disagreement extends SuppressedBy("disagreement")
    case object ProtectedLockOrCertifiedTransition extends SuppressedBy("protected_lock_or_certified_transition")
    case object StateTransitionFailed extends SuppressedBy("state_transition_failed")
    case object Unsuppressed extends SuppressedBy("none")

    val values: List[SuppressedBy] = List(
      Threshold,
      NoCandidates,
      InFlight,
      Cooldown,
      ProbeTimeout,
      ProbeError,
      NoResponders,
      RespondersBelowKey,
      InsufficientCorroborators,
      Disagreement,
      ProtectedLockOrCertifiedTransition,
      StateTransitionFailed,
      Unsuppressed
    )
  }

  /** Deterministic explanation of an escalation decision, evaluated in enum order. Pure and total; it reads the same inputs the decision
    * used plus the probe's reason data, and never feeds back into `EscalationSignal.decide`.
    *
    *   - `threshold`: the consecutive/retriable count has not reached the recovery threshold, so no evidence was gathered.
    *   - `no_candidates`: the fast path did not fire and there was no Ready HTTP peer to ask (nothing to probe, nothing to fetch).
    *   - `in_flight` / `cooldown`: the B3' coordinator declined to run the evidence unit (another unit in flight; inside the post-
    *     completion cooldown or the residence gate). A late result whose parent/generation moved on is reported as `probe_error`.
    *   - `probe_timeout` / `probe_error`: the preflight ran and degraded (or was required but produced no result).
    *   - `no_responders`: every sampled peer timed out or errored.
    *   - `responders_below_key`: responders exist but all committed strictly below the key (the cluster-wide-stall answer).
    *   - `disagreement`: responders at the corroborating ordinal reported more than one hash.
    *   - `insufficient_corroborators`: a single identity at/above the key, but fewer matching peers than the floor or not a strict
    *     responder majority.
    *   - `none`: the decision escalated.
    *
    * `protected_lock_or_certified_transition` and `state_transition_failed` are attributed at their own boundaries (the abandon-drain
    * safety checks and the recovery state transition), not by this function.
    */
  def suppressedBy(
    thresholdMet: Boolean,
    readyPeerCount: Int,
    signal: EscalationSignal,
    probe: PeersAheadProbe,
    escalated: Boolean,
    scheduling: Option[SuppressedBy] = None
  ): SuppressedBy =
    if (escalated) SuppressedBy.Unsuppressed
    else if (!thresholdMet) SuppressedBy.Threshold
    else if (!signal.probeRequired(readyPeerCount)) SuppressedBy.NoCandidates
    else scheduling.getOrElse(probeSuppressedBy(probe))

  /** Probe-only tail of `suppressedBy`, for the locked-attempt path where the probe is unconditional and no rumor signal is computed. A
    * completed probe that sampled nobody had no candidates to ask.
    */
  def probeSuppressedBy(probe: PeersAheadProbe): SuppressedBy =
    probe.outcome match {
      case ProbeOutcome.TimedOut                     => SuppressedBy.ProbeTimeout
      case ProbeOutcome.Failed | ProbeOutcome.NotRun => SuppressedBy.ProbeError
      case ProbeOutcome.Completed =>
        if (probe.confirmedAhead) SuppressedBy.Unsuppressed
        else if (probe.probedPeers == 0) SuppressedBy.NoCandidates
        else if (probe.respondedPeers == 0) SuppressedBy.NoResponders
        else if (probe.atKeyPeers + probe.aboveKeyPeers == 0) SuppressedBy.RespondersBelowKey
        else if (probe.hashesAtCorroboratingOrdinal > 1) SuppressedBy.Disagreement
        else SuppressedBy.InsufficientCorroborators
    }

  /** Stale-key telemetry rate limiter (D1). One initial WARN per key, then at most one reminder per `reminderInterval`, shared by both
    * capture sites (the monitor's same-key suppression boundary and `performAbandon`) so retries at the same key cannot re-open the budget.
    * Reset ONLY by `resetOnSuccessfulRound` (accepted consensus progress): abandonment also emits `RoundCompleted`, so that command must
    * not reset it. Entries are bounded by evicting keys below the installed parent on every capture plus a hard cap on retained keys.
    *
    * Also keeps the monotonic instant an external Facility was last observed for the tracked key (fed by the monitor each tick from the
    * declaration map, so its resolution is one monitor tick) and the monotonic instant each key was first observed locally, which survives
    * same-key retries and is what `residenceMs` reports.
    */
  final class StaleKeyTelemetry[F[_]: Async, Key: Order](
    ref: Ref[F, StaleKeyTelemetry.State[Key]],
    now: F[FiniteDuration],
    reminderInterval: FiniteDuration,
    maxEntries: Int
  ) {
    import StaleKeyTelemetry._

    /** Record that `key` is under local attempt and how many external Facilities the monitor currently sees for it. */
    def observe(key: Key, externalFacilities: Int): F[Unit] =
      now.flatMap { at =>
        ref.update { state =>
          val entry = state.entries.getOrElse(key, Entry(firstSeenAt = at))
          val facilityArrived = externalFacilities > entry.externalFacilities
          state.copy(
            entries = state.entries.updated(key, entry.copy(externalFacilities = math.max(entry.externalFacilities, externalFacilities))),
            lastExternalFacilityAt = if (facilityArrived) at.some else state.lastExternalFacilityAt
          )
        }
      }

    /** Monotonic residence of `key` (time since first observed locally, kept across same-key retries), if tracked. */
    def residenceOf(key: Key): F[Option[FiniteDuration]] =
      (now, ref.get).tupled.map { case (at, state) => state.entries.get(key).map(entry => at - entry.firstSeenAt) }

    /** Monotonic time since the last observed external Facility this session, or `None` if none was ever observed. */
    def lastExternalFacilityAgo: F[Option[FiniteDuration]] =
      (now, ref.get).tupled.map { case (at, state) => state.lastExternalFacilityAt.map(at - _) }

    /** Decide whether a WARN may be emitted for `key` now. Evicts keys below `installedParent` and enforces the cap. `force` bypasses the
      * reminder budget (the emission still counts toward it).
      */
    def capture(key: Key, installedParent: Key, force: Boolean = false): F[Option[Emission]] =
      now.flatMap { at =>
        ref.modify { state =>
          val pruned = state.entries.filter { case (k, _) => Order[Key].gteqv(k, installedParent) }
          val entry = pruned.getOrElse(key, Entry(firstSeenAt = at))
          val due = force || entry.lastWarnAt.fold(true)(last => at - last >= reminderInterval)
          val emission =
            Option.when(due)(
              Emission(
                kind = if (entry.warnCount == 0) "initial" else "reminder",
                warnsSoFar = entry.warnCount,
                residence = at - entry.firstSeenAt,
                lastExternalFacilityAgo = state.lastExternalFacilityAt.map(at - _)
              )
            )
          val nextEntry = if (due) entry.copy(lastWarnAt = at.some, warnCount = entry.warnCount + 1) else entry
          val updated = pruned.updated(key, nextEntry)
          val capped =
            if (updated.size > maxEntries)
              updated.toList.sortBy { case (k, _) => k }(Order[Key].toOrdering).takeRight(maxEntries).toMap
            else updated
          (state.copy(entries = capped), emission)
        }
      }

    /** Accepted consensus progress: forget every tracked key. The last-external-Facility instant is session-level and is kept. */
    def reset: F[Unit] = ref.update(_.copy(entries = Map.empty))

    def trackedKeys: F[Set[Key]] = ref.get.map(_.entries.keySet)
  }

  object StaleKeyTelemetry {
    val ReminderInterval: FiniteDuration = 1.minute
    val MaxEntries: Int = 32

    final case class Entry(
      firstSeenAt: FiniteDuration,
      lastWarnAt: Option[FiniteDuration] = None,
      warnCount: Int = 0,
      externalFacilities: Int = 0
    )

    final case class State[Key](entries: Map[Key, Entry], lastExternalFacilityAt: Option[FiniteDuration])

    object State {
      def empty[Key]: State[Key] = State(Map.empty, None)
    }

    final case class Emission(kind: String, warnsSoFar: Int, residence: FiniteDuration, lastExternalFacilityAgo: Option[FiniteDuration])

    def make[F[_]: Async, Key: Order](
      now: F[FiniteDuration],
      reminderInterval: FiniteDuration = ReminderInterval,
      maxEntries: Int = MaxEntries
    ): F[StaleKeyTelemetry[F, Key]] =
      Ref.of[F, State[Key]](State.empty[Key]).map(new StaleKeyTelemetry[F, Key](_, now, reminderInterval, maxEntries))

    def unsafe[F[_]: Async, Key: Order]: StaleKeyTelemetry[F, Key] =
      new StaleKeyTelemetry[F, Key](Ref.unsafe(State.empty[Key]), Async[F].monotonic, ReminderInterval, MaxEntries)
  }
}
