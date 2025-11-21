package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.kernel.Ref
import cats.effect.{Sync, SyncIO}
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
  * PendingTriggers stores at most one pending trigger with priority:
  *   - Time triggers have higher priority than Event triggers
  *   - If both arrive, Time wins
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
final case class PendingTriggers(
  eventPending: Ref[SyncIO, Boolean],
  timePending: Ref[SyncIO, Boolean]
)

object PendingTriggers {

  def create[F[_]: Sync]: F[PendingTriggersF[F]] =
    for {
      eventRef <- Ref.of[F, Boolean](false)
      timeRef <- Ref.of[F, Boolean](false)
    } yield new PendingTriggersF[F](eventRef, timeRef)

}

final class PendingTriggersF[F[_]: Sync](
  private val eventRef: Ref[F, Boolean],
  private val timeRef: Ref[F, Boolean]
) {

  def setEvent(): F[Unit] = eventRef.set(true)

  def setTime(): F[Unit] = timeRef.set(true)

  def pullNext: F[Option[TriggerPriority]] =
    for {
      time <- timeRef.get
      event <- eventRef.get

      _ <- timeRef.set(false)
      _ <- eventRef.set(false)
    } yield
      if (time) Some(TriggerPriority.Time)
      else if (event) Some(TriggerPriority.Event)
      else None
}

sealed trait TriggerPriority
object TriggerPriority {
  case object Time extends TriggerPriority
  case object Event extends TriggerPriority
}
