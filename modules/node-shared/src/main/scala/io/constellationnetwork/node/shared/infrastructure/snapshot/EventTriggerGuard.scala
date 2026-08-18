package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.effect.{Async, Clock, Ref}
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool

import org.typelevel.log4cats.SelfAwareStructuredLogger

/** Shared guard logic for event-driven consensus triggering.
  *
  * Checks pass when:
  *   1. `triggerEventConsensus` is available (consensus wired) 2. Last round had >= 2 facilitators (not solo genesis) 3. The caller has >=
  *      `threshold` new trigger intents (batch efficiency) 4. Cooldown elapsed since last trigger (prevent rapid-fire)
  *
  * The solo-genesis guard uses `lastFacilitatorCount` rather than peer count because `getResponsivePeers` excludes self, which would
  * incorrectly block event triggers in 2-node clusters. The facilitator count reflects actual consensus participation: solo genesis has 1,
  * multi-node has 2+.
  *
  * The caller owns the distinction between stored work and new trigger intent. Events can remain in the mempool for TimeTrigger processing
  * without continuously re-arming EventTrigger.
  */
object EventTriggerGuard {

  sealed abstract class Decision(val label: String)
  object Decision {
    case object Disabled extends Decision("disabled")
    case object InsufficientParticipants extends Decision("insufficient_participants")
    case object BelowThreshold extends Decision("below_threshold")
    case object CooldownActive extends Decision("cooldown_active")
    case object Fired extends Decision("fired")
  }

  /** Compatibility entry point for layers whose trigger semantics intentionally remain based on their active mempool size. */
  def apply[F[_]: Async, E, K](
    eventMempool: EventMempool[F, E, K],
    triggerEventConsensus: Option[F[Unit]],
    getLastFacilitatorCount: F[Int],
    lastTriggerRef: Ref[F, Long],
    logger: SelfAwareStructuredLogger[F],
    threshold: Int,
    cooldown: FiniteDuration
  ): F[Unit] =
    evaluateLazy(
      triggerEventConsensus,
      getLastFacilitatorCount,
      lastTriggerRef,
      logger,
      eventMempool.size,
      threshold,
      cooldown
    ).void

  /** Evaluate an explicit trigger-intent count. GL0 uses this to keep durable mempool work separate from new scheduling demand. */
  def evaluate[F[_]: Async](
    triggerEventConsensus: Option[F[Unit]],
    getLastFacilitatorCount: F[Int],
    lastTriggerRef: Ref[F, Long],
    logger: SelfAwareStructuredLogger[F],
    pendingEventCount: Int,
    threshold: Int,
    cooldown: FiniteDuration
  ): F[Decision] =
    evaluateLazy(
      triggerEventConsensus,
      getLastFacilitatorCount,
      lastTriggerRef,
      logger,
      pendingEventCount.pure[F],
      threshold,
      cooldown
    )

  /** Keep pending-count evaluation behind the enabled and participant gates. Besides avoiding unnecessary work, this preserves the legacy
    * Currency L0 effect ordering exactly while allowing GL0 to supply explicit trigger intent.
    */
  private def evaluateLazy[F[_]: Async](
    triggerEventConsensus: Option[F[Unit]],
    getLastFacilitatorCount: F[Int],
    lastTriggerRef: Ref[F, Long],
    logger: SelfAwareStructuredLogger[F],
    getPendingEventCount: F[Int],
    threshold: Int,
    cooldown: FiniteDuration
  ): F[Decision] =
    triggerEventConsensus match {
      case None => Decision.Disabled.pure[F].widen[Decision]
      case Some(trigger) =>
        for {
          lastFacCount <- getLastFacilitatorCount
          decision <-
            if (lastFacCount > 0 && lastFacCount < 2)
              logger.debug(
                s"EventTrigger skipped: last round had $lastFacCount facilitator(s), waiting for multi-node consensus"
              ) >> Decision.InsufficientParticipants.pure[F].widen[Decision]
            else
              getPendingEventCount.flatMap { pendingEventCount =>
                if (pendingEventCount < threshold)
                  Decision.BelowThreshold.pure[F].widen[Decision]
                else
                  Clock[F].monotonic.flatMap { now =>
                    val nowMs = now.toMillis
                    lastTriggerRef.modify { lastMs =>
                      val elapsed = nowMs - lastMs
                      if (elapsed >= cooldown.toMillis)
                        (nowMs, true)
                      else
                        (lastMs, false)
                    }.flatMap {
                      case true =>
                        logger.info(
                          s"EventTrigger fired: lastFacilitators=$lastFacCount, pending=$pendingEventCount, " +
                            s"threshold=$threshold, cooldown=${cooldown.toSeconds}s"
                        ) >> trigger.as[Decision](Decision.Fired)
                      case false =>
                        Decision.CooldownActive.pure[F].widen[Decision]
                    }
                  }
              }
        } yield decision
    }
}
