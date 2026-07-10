package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.Functor
import cats.effect.Sync
import cats.effect.kernel.Ref
import cats.syntax.all._

/** Tracks pending triggers that arrived while a consensus round was running.
  *
  * ==Problem==
  *
  * When a consensus round is in progress (isRunning = true), we can't start another. But triggers (TimeTick, FacilitateByEvent) may arrive
  * during this time. We need to remember them so we can start the next round when current finishes.
  *
  * ==Solution==
  *
  * Uses a single atomic Ref to avoid the race condition of reading/clearing two separate Refs. Time triggers have higher priority than
  * Event triggers. If both arrive, Time wins.
  *
  * ==Usage==
  * {{{
  *   // During busy state:
  *   pending.setTime    // TimeTick arrived
  *   pending.setEvent   // FacilitateByEvent arrived
  *
  *   // After round completes:
  *   pending.pullNext match {
  *     case Some(Time)  => queue.offer(StartRound(TimeTrigger))
  *     case Some(Event) => queue.offer(StartRound(EventTrigger))
  *     case None        => // Wait for next trigger
  *   }
  * }}}
  */
object PendingTriggers {

  private[engine] sealed trait PendingState
  private[engine] case object NoPending extends PendingState
  private[engine] case object EventPending extends PendingState
  private[engine] case object TimePending extends PendingState

  def create[F[_]: Sync]: F[PendingTriggersF[F]] =
    Ref.of[F, PendingState](NoPending).map(new PendingTriggersF[F](_))
}

final class PendingTriggersF[F[_]: Functor] private[engine] (
  private val stateRef: Ref[F, PendingTriggers.PendingState]
) {

  import PendingTriggers._

  def setEvent(): F[Unit] = stateRef.update {
    case TimePending => TimePending // Don't downgrade time to event
    case _           => EventPending
  }

  def setTime(): F[Unit] = stateRef.set(TimePending)

  /** Clears any pending trigger without returning it. Used during recovery to prevent stale triggers from starting a new round while the
    * node is downloading state.
    */
  def clear(): F[Unit] = stateRef.set(NoPending)

  /** Atomically retrieves and clears the pending trigger. */
  def pullNext: F[Option[TriggerPriority]] =
    stateRef.getAndSet(NoPending).map {
      case TimePending  => Some(TriggerPriority.Time)
      case EventPending => Some(TriggerPriority.Event)
      case NoPending    => None
    }
}

sealed trait TriggerPriority
object TriggerPriority {
  case object Time extends TriggerPriority
  case object Event extends TriggerPriority
}
