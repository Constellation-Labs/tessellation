package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.Eq
import cats.effect.kernel.{Async, Ref}
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.node.{NodeState, NodeStateTransition}

import eu.timepit.refined.auto._

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
  * If the node enters a recovery loop (abandon → download → come back to same state → abandon → download → ...), a total recovery
  * attempt counter eventually forces the node to `Leaving` state. This breaks pathological loops where the downloaded state itself
  * leads to the same stuck ordinal. The hard limit is `maxConsecutiveAbandonments * 3` (default: 15 recovery attempts).
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

  /** Tracks total recovery download attempts across all keys to detect extended recovery loops. */
  private val totalRecoveryAttemptsRef: Ref[F, Int] = Ref.unsafe(0)

  /** Abandon a round: clear state, track consecutive failures, and either retry or trigger recovery. */
  def abandonRound(key: Key, reason: String): F[Unit] =
    ConsensusLog.error(logger, ConsensusLog.Lifecycle, key.toString, "n/a", "event" -> "ROUND_ABANDONED", "reason" -> reason) >>
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
      storage.clearResources(key) >>
      trackConsecutiveAbandonments(key).flatMap { consecutiveCount =>
        val shouldRecover = consecutiveCount >= config.maxConsecutiveAbandonments
        healthRef.update(_.copy(consecutiveAbandonments = consecutiveCount)) >>
          ConsensusLog.info(
            logger,
            ConsensusLog.Lifecycle,
            key.toString,
            "n/a",
            "event" -> "ROUND_ABANDONED_TRACKED",
            "consecutiveAbandonments" -> consecutiveCount.toString,
            "maxConsecutiveAbandonments" -> config.maxConsecutiveAbandonments.toString,
            "triggerRecovery" -> shouldRecover.toString
          ) >>
          (if (shouldRecover)
             triggerRecoveryDownload(key, consecutiveCount)
           else
             queue.offer(ConsensusCommand.RoundCompleted) >>
               queue.offer(ConsensusCommand.TimeTick))
      }

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

  /** Hard limit for total recovery attempts before forcing the node to leave the cluster.
    * Default: 3 * maxConsecutiveAbandonments (e.g., 15 if maxConsecutiveAbandonments=5).
    */
  private val maxTotalRecoveryAttempts: Int = config.maxConsecutiveAbandonments * 3

  private def triggerRecoveryDownload(key: Key, consecutiveCount: Int): F[Unit] =
    totalRecoveryAttemptsRef.updateAndGet(_ + 1).flatMap { totalAttempts =>
      val shouldForceLeave = totalAttempts >= maxTotalRecoveryAttempts

      ConsensusLog.error(
        logger,
        ConsensusLog.Lifecycle,
        key.toString,
        "n/a",
        "event" -> (if (shouldForceLeave) "FORCE_LEAVE_TRIGGERED" else "RECOVERY_DOWNLOAD_TRIGGERED"),
        "consecutiveAbandonments" -> consecutiveCount.toString,
        "totalRecoveryAttempts" -> totalAttempts.toString,
        "maxTotalRecoveryAttempts" -> maxTotalRecoveryAttempts.toString,
        "reason" -> (if (shouldForceLeave)
                       s"extended recovery loop: $totalAttempts recovery attempts exhausted, forcing node to leave"
                     else
                       s"stuck at same ordinal for $consecutiveCount consecutive rounds")
      ) >>
        Metrics[F].incrementCounter("dag_consensus_recovery_download_triggered") >>
        (if (shouldForceLeave)
           Metrics[F].incrementCounter("dag_consensus_force_leave_triggered") >>
             forceLeave(key, totalAttempts)
         else
           attemptRecoveryDownload(key))
    }

  /** Force the node to leave the cluster after exhausting all recovery attempts.
    * This breaks pathological loops where downloaded state leads to the same stuck ordinal.
    */
  private def forceLeave(key: Key, totalAttempts: Int): F[Unit] =
    ctx.nodeStorage.tryModifyStateGetResult(NodeState.Ready, NodeState.Leaving).flatMap {
      case NodeStateTransition.Success =>
        ConsensusLog.error(
          logger,
          ConsensusLog.Lifecycle,
          key.toString,
          "n/a",
          "event" -> "FORCE_LEAVE_SUCCESS",
          "totalRecoveryAttempts" -> totalAttempts.toString,
          "reason" -> "node leaving cluster after extended recovery loop"
        ) >>
          consecutiveAbandonCountRef.set((none[Key], 0)) >>
          totalRecoveryAttemptsRef.set(0) >>
          healthRef.update(_.copy(consecutiveAbandonments = 0, totalRecoveryAttempts = 0)) >>
          ctx.pending.clear() >>
          queue.offer(ConsensusCommand.RoundCompleted)
      case _ =>
        // If we can't transition to Leaving, fall back to normal recovery download
        ConsensusLog.warn(
          logger,
          ConsensusLog.Lifecycle,
          key.toString,
          "n/a",
          "event" -> "FORCE_LEAVE_FAILED",
          "reason" -> "node not in Ready state, falling back to recovery download"
        ) >>
          attemptRecoveryDownload(key)
    }

  private def attemptRecoveryDownload(key: Key): F[Unit] =
    ctx.nodeStorage.tryModifyStateGetResult(NodeState.Ready, NodeState.WaitingForDownload).flatMap {
      case NodeStateTransition.Success =>
        ConsensusLog.info(
          logger,
          ConsensusLog.Lifecycle,
          key.toString,
          "n/a",
          "event" -> "RECOVERY_STATE_TRANSITION",
          "from" -> "Ready",
          "to" -> "WaitingForDownload"
        ) >>
          consecutiveAbandonCountRef.set((none[Key], 0)) >>
          healthRef.update(_.copy(consecutiveAbandonments = 0)) >>
          ctx.pending.clear() >>
          queue.offer(ConsensusCommand.RoundCompleted)
      case _ =>
        ConsensusLog.warn(
          logger,
          ConsensusLog.Lifecycle,
          key.toString,
          "n/a",
          "event" -> "RECOVERY_TRANSITION_FAILED",
          "reason" -> "node not in Ready state"
        ) >>
          queue.offer(ConsensusCommand.RoundCompleted) >>
          queue.offer(ConsensusCommand.TimeTick)
    }
}
