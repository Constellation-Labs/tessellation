package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.kernel.{Async, Ref}
import cats.syntax.all._
import cats.{Eq, Show}

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
}

object AbandonReason {

  /** Not enough peers to reach quorum — wait for peers to come back. */
  final case class QuorumInfeasible(active: Int, required: Int, clusterSize: Int) extends AbandonReason {
    def message: String = s"quorum infeasible: $active active < $required required (clusterSize=$clusterSize)"
    def label: String = "quorum_infeasible"
    def retriable: Boolean = true
  }

  /** This node is behind the network — peers are at a higher ordinal. */
  final case class Lagging(peersAhead: Int, totalPeers: Int, totalRegs: Int) extends AbandonReason {
    def message: String = s"lagging behind network: $peersAhead/$totalPeers ready peers at higher key (totalRegs=$totalRegs)"
    def label: String = "lagging"
    def retriable: Boolean = false
  }

  /** Repeated eviction attempts blocked (below minimum facilitators), cycling without progress. */
  case object EvictionLoopStuck extends AbandonReason {
    def message: String = "eviction loop: repeated eviction skips (below minimum facilitators), escalating to abandon"
    def label: String = "eviction_loop"
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
class AbandonmentTracker[F[_]: Async: Metrics, Event, Key: Eq, Artifact, Ctx, Status, Outcome, Kind](
  ctx: ConsensusEngineContext[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind],
  healthRef: Ref[F, ConsensusHealthStatus]
) {

  import ctx.{config, logger, peerQualityTracker, queue, storage}

  /** Tracks consecutive abandonments at the same key to detect infinite stuck loops. */
  private val consecutiveAbandonCountRef: Ref[F, (Option[Key], Int)] = Ref.unsafe((none[Key], 0))

  /** Tracks consecutive retriable abandonments at the same key. If the node is stuck at the same ordinal with quorum-infeasible for too
    * long (e.g., post-chaos where one node forked ahead), this escalates to non-retriable after `maxRetriableAtSameKey` attempts.
    */
  private val retriableAtSameKeyRef: Ref[F, (Option[Key], Int)] = Ref.unsafe((none[Key], 0))

  /** After this many retriable abandonments at the same ordinal, escalate to recovery. Default: 1x maxConsecutiveAbandonments (5 with
    * default config). This is higher than the non-retriable threshold because quorum-infeasible is expected during transient partitions.
    */
  private val maxRetriableAtSameKey: Int = config.maxConsecutiveAbandonments * 1

  /** Tracks total recovery download attempts across all keys to detect extended recovery loops. */
  private val totalRecoveryAttemptsRef: Ref[F, Int] = Ref.unsafe(0)

  /** Reset recovery counters after a successful consensus round. This prevents a node that recovered successfully from carrying stale
    * recovery history that could trigger premature force-leave on a future (unrelated) recovery.
    */
  def resetOnSuccessfulRound: F[Unit] =
    totalRecoveryAttemptsRef.set(0) >>
      retriableAtSameKeyRef.set((none[Key], 0)) >>
      healthRef.update(_.copy(totalRecoveryAttempts = 0))

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
          queue.offer(ConsensusCommand.RoundCompleted)
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
              queue.offer(ConsensusCommand.RoundCompleted)
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
    */
  def abandonRound(key: Key, reason: AbandonReason): F[Unit] =
    ConsensusLog.error(logger, Category.Lifecycle, key.toString, "n/a", LogEvent.RoundAbandoned, "reason" -> reason.message) >>
      Metrics[F].incrementCounter("dag_consensus_round_abandoned") >>
      Metrics[F].incrementCounter("dag_consensus_stall_abandon_reason", Seq((Metrics.unsafeLabelName("reason"), reason.label))) >>
      storage
        .condModifyState[Unit](key) {
          case Some(state) =>
            peerQualityTracker
              .recordRoundAbandoned(state.facilitators.value.toSet)
              .as((none[ConsensusState[Key, Status, Outcome, Kind]], ()).some)
          case _ =>
            none[(Option[ConsensusState[Key, Status, Outcome, Kind]], Unit)].pure[F]
        }
        .void
        .handleErrorWith(e => logger.warn(e)("condModifyState failed during abandon, proceeding with resource cleanup")) >>
      storage.clearResources(key) >>
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
                // Stuck at the same ordinal with quorum-infeasible for too long.
                // Determine whether this node is ISOLATED (should escalate to recovery)
                // or the WHOLE CLUSTER is stuck (kill-4, should NOT escalate).
                //
                // The key signal is the last round's active facilitator count from the
                // QuorumInfeasible reason:
                //   active == 1: only this node participated → isolated, peers moved on
                //   active > 1: multiple peers participated → cluster-wide stall, recovery
                //               would deadlock with no Ready peers to serve downloads
                //
                // This is more reliable than peer registrations, which go stale in long-running
                // clusters (registrations stay at join-time ordinal, far below current).
                {
                  val activeFacilitators = reason match {
                    case AbandonReason.QuorumInfeasible(active, _, _) => active
                    case other                                        => throw new MatchError(s"Unexpected retriable AbandonReason: $other")
                  }
                  val isIsolated = activeFacilitators <= 1
                  if (isIsolated)
                    // Only this node (or nobody) participated — we're isolated from the
                    // network. Peers have moved on. Trigger recovery download.
                    retriableAtSameKeyRef.set((none[Key], 0)) >>
                      trackConsecutiveAbandonments(key).flatMap { consecutiveCount =>
                        ConsensusLog.info(
                          logger,
                          Category.Lifecycle,
                          key.toString,
                          "n/a",
                          LogEvent.RetriableEscalated,
                          "reason" -> reason.label,
                          "activeFacilitators" -> activeFacilitators.toString
                        ) >>
                          healthRef.update(_.copy(consecutiveAbandonments = consecutiveCount)) >>
                          triggerRecoveryDownload(key, consecutiveCount)
                      }
                  else
                    // Multiple peers participated but couldn't form quorum — cluster-wide
                    // stall (e.g. kill-4). Keep retrying; recovery download would deadlock
                    // with no Ready peers to serve downloads.
                    // NOTE: If all nodes lose connectivity to each other simultaneously,
                    // each sees remaining=1 and ALL escalate — same deadlock. This is a
                    // known limitation; external monitoring restart is the mitigation.
                    ConsensusLog.info(
                      logger,
                      Category.Lifecycle,
                      key.toString,
                      "n/a",
                      LogEvent.RoundAbandonedRetriable,
                      "reason" -> reason.label,
                      "detail" -> s"escalation suppressed: multi-peer stall (activeFacilitators=$activeFacilitators)",
                      "retriableAtSameKey" -> retriableCount.toString,
                      "maxRetriableAtSameKey" -> maxRetriableAtSameKey.toString
                    ) >>
                      queue.offer(ConsensusCommand.RoundCompleted) >>
                      queue.offer(ConsensusCommand.TimeTick)
                } else
                queue.offer(ConsensusCommand.RoundCompleted) >>
                  queue.offer(ConsensusCommand.TimeTick))
         }
       else
         trackConsecutiveAbandonments(key).flatMap { consecutiveCount =>
           val shouldRecover = consecutiveCount >= config.maxConsecutiveAbandonments
           healthRef.update(_.copy(consecutiveAbandonments = consecutiveCount)) >>
             ConsensusLog.info(
               logger,
               Category.Lifecycle,
               key.toString,
               "n/a",
               LogEvent.RoundAbandonedTracked,
               "reason" -> reason.label,
               "consecutiveAbandonments" -> consecutiveCount.toString,
               "maxConsecutiveAbandonments" -> config.maxConsecutiveAbandonments.toString,
               "triggerRecovery" -> shouldRecover.toString
             ) >>
             (if (shouldRecover)
                triggerRecoveryDownload(key, consecutiveCount)
              else
                queue.offer(ConsensusCommand.RoundCompleted) >>
                  queue.offer(ConsensusCommand.TimeTick))
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

  private def triggerRecoveryDownload(key: Key, consecutiveCount: Int): F[Unit] =
    totalRecoveryAttemptsRef.updateAndGet(_ + 1).flatMap { totalAttempts =>
      val shouldForceLeave = totalAttempts >= maxTotalRecoveryAttempts

      (if (shouldForceLeave)
         ConsensusLog.error(
           logger,
           Category.Lifecycle,
           key.toString,
           "n/a",
           LogEvent.ForceLeaveTriggered,
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
           "consecutiveAbandonments" -> consecutiveCount.toString,
           "totalRecoveryAttempts" -> totalAttempts.toString,
           "maxTotalRecoveryAttempts" -> maxTotalRecoveryAttempts.toString,
           "reason" -> s"stuck at same ordinal for $consecutiveCount consecutive rounds"
         )) >>
        healthRef.update(_.copy(totalRecoveryAttempts = totalAttempts)) >>
        Metrics[F].incrementCounter("dag_consensus_recovery_download_triggered") >>
        (if (shouldForceLeave)
           Metrics[F].incrementCounter("dag_consensus_force_leave_triggered") >>
             forceLeave(key, totalAttempts)
         else
           attemptRecoveryDownload(key))
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
          queue.offer(ConsensusCommand.RoundCompleted)
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
              queue.offer(ConsensusCommand.RoundCompleted)
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
              attemptRecoveryDownload(key)
        }
      }
    }
  }

  private def attemptRecoveryDownload(key: Key): F[Unit] = {
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
    ctx.nodeStorage.setRecoveryDownload >>
      tryStates(recoveryStates).flatMap {
        case Some(fromState) =>
          ConsensusLog.info(
            logger,
            Category.Lifecycle,
            key.toString,
            "n/a",
            LogEvent.RecoveryStateTransition,
            "from" -> fromState.toString,
            "to" -> "WaitingForDownload"
          ) >>
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
            queue.offer(ConsensusCommand.RoundCompleted)
        case None =>
          // Check if node is already in Leaving state — if so, just complete the round and stop.
          // CRITICAL: Do NOT queue TimeTick here. The old code queued RoundCompleted + TimeTick,
          // which created an infinite tight loop when node is in Leaving state:
          //   TimeTick → startRound → abandon → forceLeave(fails) → recoveryDownload(fails) → TimeTick → ...
          // By only queuing RoundCompleted (no TimeTick), the loop terminates after this iteration.
          // The next round will only start when an external trigger arrives (peer event, timer, etc.)
          ctx.nodeStorage.getNodeState.flatMap { currentState =>
            ConsensusLog.warn(
              logger,
              Category.Lifecycle,
              key.toString,
              "n/a",
              LogEvent.RecoveryTransitionFailed,
              "reason" -> s"node in $currentState state, not Ready or Observing",
              "nodeState" -> currentState.show
            ) >>
              ctx.pending.clear() >>
              queue.offer(ConsensusCommand.RoundCompleted)
          }
      }
  }
}
