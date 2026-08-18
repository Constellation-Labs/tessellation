package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.kernel.{Async, Ref}
import cats.syntax.all._
import cats.{Order, Show}

import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event => LogEvent}
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.node.{NodeState, NodeStateTransition}

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
  peersCommittedAheadProbe: Key => F[AbandonmentTracker.PeersAheadProbe]
) {

  import ctx.{clusterStorage, config, logger, peerQualityTracker, queue, storage}
  import AbandonmentTracker.EscalationCause

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
      healthRef.update(_.copy(totalRecoveryAttempts = 0, wedgeDetectedAtMs = None))

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
                    peersCommittedAheadProbe(key)
                      .handleError(_ => AbandonmentTracker.PeersAheadProbe.failed)
                      .flatMap { probe =>
                        val action = AbandonmentTracker.lockedAttemptAction(reason, probe)
                        val observe = ConsensusLog.warn(
                          logger,
                          Category.Recovery,
                          key.toString,
                          "n/a",
                          LogEvent.RoundAbandoned,
                          "reason" -> reason.label,
                          "action" -> action.label,
                          "view" -> state.viewNumber.toString,
                          "phaseIndex" -> ctx.ops.phaseIndex(state.status).toString,
                          "highestVotedView" -> voteLock.flatMap(_.highestVotedView).fold("none")(_.toString),
                          "lockedQcView" -> voteLock.flatMap(_.lockedQc).fold("none")(_.view.toString),
                          "probeConfirmedAhead" -> probe.confirmedAhead.toString,
                          "probeOutcome" -> probe.outcome.label,
                          "probeResponded" -> s"${probe.respondedPeers}/${probe.probedPeers}",
                          "probeCorroborators" -> probe.corroboratingPeers.toString
                        ) >> Metrics[F].incrementCounter(
                          "dag_consensus_locked_lagging_recovery_probe_total",
                          Seq(
                            Metrics.unsafeLabelName("action") -> action.label,
                            Metrics.unsafeLabelName("outcome") -> probe.outcome.label
                          )
                        )

                        observe.attempt.void >> (action match {
                          case AbandonmentTracker.LockedAttemptAction.RecoverByDownload =>
                            attemptRecoveryDownload(
                              key,
                              reason.label,
                              "locked_lagging_corroborated",
                              retainRoundOnTransitionFailure = true,
                              preferFollowerCatchUp = AbandonmentTracker.followerCatchUpEligible(reason)
                            )
                          case AbandonmentTracker.LockedAttemptAction.Retain => Async[F].unit
                        })
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
                      Metrics[F].incrementCounter("dag_consensus_abandon_skipped_same_key_lock_total")
                }
              case _ =>
                performAbandon(key, reason)
            }
          case _ =>
            performAbandon(key, reason)
        }
    }

  private def performAbandon(key: Key, reason: AbandonReason): F[Unit] =
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
                            peerCurrentKeys <- storage.getPeerCurrentKeys
                            responsivePeers <- clusterStorage.getResponsivePeers
                            readyPeerIds = responsivePeers.filter(_.state === NodeState.Ready).map(_.id).toSet
                            readyPeerRegs = peerCurrentKeys.view.filterKeys(readyPeerIds.contains).toMap
                            peersAtHigherKey = readyPeerRegs.count { case (_, peerKey) => peerKey > key }
                            peersAtSameKey = readyPeerRegs.count { case (_, peerKey) => peerKey === key }
                            // Fast path: rumor tips above the key escalate directly. Every other
                            // shape (all-below, at-key, empty map) is ambiguous between isolation
                            // and a cluster-wide stall, so whenever HTTP-Ready peers exist the
                            // preflight asks them for committed progress -- escalation requires a
                            // corroborated `(ordinal, hash)` at/above the key. A genuine
                            // cluster-wide stall cannot corroborate it because nobody committed it.
                            // See AbandonmentTracker.EscalationSignal for the full argument.
                            signal = AbandonmentTracker.escalationSignal(key, readyPeerRegs.values)
                            probe <-
                              if (signal.probeRequired(readyPeerIds.size))
                                peersCommittedAheadProbe(key).handleError(_ => AbandonmentTracker.PeersAheadProbe.failed)
                              else AbandonmentTracker.PeersAheadProbe.none.pure[F]
                            escalate = signal.decide(probe.confirmedAhead)
                            effectiveCause = if (escalate && !signal.networkAdvanced) EscalationCause.RumorIsolated else cause
                            _ <- ConsensusLog.info(
                              logger,
                              Category.Lifecycle,
                              key.toString,
                              "n/a",
                              LogEvent.RetriableEscalated,
                              "reason" -> reason.label,
                              "activeFacilitators" -> activeFacilitators.toString,
                              "requiredQuorum" -> requiredQuorum.toString,
                              "escalationCause" -> effectiveCause.label,
                              "peersAtHigherKey" -> peersAtHigherKey.toString,
                              "peersAtSameKey" -> peersAtSameKey.toString,
                              "rumorStale" -> signal.rumorStale.toString,
                              "probeConfirmedAhead" -> probe.confirmedAhead.toString,
                              "probeOutcome" -> probe.outcome.label,
                              "probeResponded" -> s"${probe.respondedPeers}/${probe.probedPeers}",
                              "probeCorroborators" -> probe.corroboratingPeers.toString,
                              "readyPeers" -> readyPeerIds.size.toString,
                              "registeredReadyPeers" -> readyPeerRegs.size.toString,
                              "triggerRecovery" -> escalate.toString,
                              "recoverySuppressed" -> (!escalate).toString
                            )
                            _ <- healthRef.update(_.copy(consecutiveAbandonments = consecutiveCount))
                            // Update wedge signal for Cluster.leave() guard. Fires when retriable abandonments at the same key
                            // pile up AND no peer is ahead - the symptom of an orchestration-induced wedge where consensus
                            // can't close because the committee is structurally short of quorum. Clears when peersAtHigherKey > 0
                            // (cluster has advanced) or when a round closes (resetOnSuccessfulRound).
                            _ <- updateWedgeHealth(retriableCount, peersAtHigherKey, reason.label)
                            _ <-
                              if (escalate) triggerRecoveryDownload(key, consecutiveCount, reason.label, effectiveCause.label)
                              else retryAfterRetriableAbandon(key, reason)
                          } yield ()
                        }
                  }
              else
                retryAfterRetriableAbandon(key, reason))
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
             peerCurrentKeys <- storage.getPeerCurrentKeys
             responsivePeers <- clusterStorage.getResponsivePeers
             readyPeerIds = responsivePeers.filter(_.state === NodeState.Ready).map(_.id).toSet
             readyPeerRegs = peerCurrentKeys.view.filterKeys(readyPeerIds.contains).toMap
             peersAtHigherKey = readyPeerRegs.count { case (_, peerKey) => peerKey > key }
             peersAtSameKey = readyPeerRegs.count { case (_, peerKey) => peerKey === key }
             // Same evidence + preflight composition as the retriable path; the probe only runs
             // once the recovery threshold is met AND the fast path has not fired AND there are
             // Ready peers to ask, so pre-threshold abandonment cycles never generate probe
             // traffic.
             signal = AbandonmentTracker.escalationSignal(key, readyPeerRegs.values)
             probe <-
               if (shouldRecover && signal.probeRequired(readyPeerIds.size))
                 peersCommittedAheadProbe(key).handleError(_ => AbandonmentTracker.PeersAheadProbe.failed)
               else AbandonmentTracker.PeersAheadProbe.none.pure[F]
             willRecover = shouldRecover && signal.decide(probe.confirmedAhead)
             recoveryCause = if (willRecover && !signal.networkAdvanced) EscalationCause.RumorIsolated.label else "non_retriable"
             _ <- healthRef.update(_.copy(consecutiveAbandonments = consecutiveCount))
             _ <- ConsensusLog.info(
               logger,
               Category.Lifecycle,
               key.toString,
               "n/a",
               LogEvent.RoundAbandonedTracked,
               "reason" -> reason.label,
               "consecutiveAbandonments" -> consecutiveCount.toString,
               "maxConsecutiveAbandonments" -> config.maxConsecutiveAbandonments.toString,
               "peersAtHigherKey" -> peersAtHigherKey.toString,
               "peersAtSameKey" -> peersAtSameKey.toString,
               "rumorStale" -> signal.rumorStale.toString,
               "probeConfirmedAhead" -> probe.confirmedAhead.toString,
               "probeOutcome" -> probe.outcome.label,
               "probeResponded" -> s"${probe.respondedPeers}/${probe.probedPeers}",
               "probeCorroborators" -> probe.corroboratingPeers.toString,
               "readyPeers" -> readyPeerIds.size.toString,
               "registeredReadyPeers" -> readyPeerRegs.size.toString,
               "triggerRecovery" -> willRecover.toString,
               "recoverySuppressed" -> (shouldRecover && !willRecover).toString
             )
             _ <-
               if (willRecover)
                 triggerRecoveryDownload(
                   key,
                   consecutiveCount,
                   reason.label,
                   recoveryCause,
                   preferFollowerCatchUp = AbandonmentTracker.followerCatchUpEligible(reason)
                 )
               else offerRoundCompleted >> queue.offer(ConsensusCommand.TimeTick)
           } yield ()
         })

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
  ): F[Unit] =
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
  private def forceLeave(key: Key, totalAttempts: Int): F[Unit] = {
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
          offerRoundCompleted
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
              offerRoundCompleted
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
  ): F[Unit] = {
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
            offerRoundCompleted
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
               else ctx.pending.clear() >> offerRoundCompleted)
          }
      }
  }
}

object AbandonmentTracker {

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
    outcome: ProbeOutcome
  )

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

  def escalationSignal[K: Order](abandonedKey: K, readyPeerKeys: Iterable[K]): EscalationSignal = {
    val higher = readyPeerKeys.count(Order[K].gt(_, abandonedKey))
    val same = readyPeerKeys.count(Order[K].eqv(_, abandonedKey))
    EscalationSignal(
      networkAdvanced = higher > 0,
      rumorStale = readyPeerKeys.nonEmpty && higher == 0 && same == 0
    )
  }
}
