package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.effect._
import cats.effect.std.{Queue, Supervisor}
import cats.syntax.all._
import cats.{Order, Show}

import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger

import org.typelevel.log4cats.Logger

sealed trait ConsensusOperation[Key]

object ConsensusOperation {
  final case class FacilitateRound[Key](trigger: Option[ConsensusTrigger]) extends ConsensusOperation[Key]

  final case class UpdateState[Key](key: Key) extends ConsensusOperation[Key]
}

trait ConsensusQueue[F[_], Key] {
  def requestFacilitation(trigger: Option[ConsensusTrigger]): F[Unit]

  def requestStateUpdate(key: Key): F[Unit]

  def clearPendingUpdate(key: Key): F[Unit]
}

object ConsensusQueue {

  def make[F[_]: Async, Key: Order: Show](
    processFacilitation: Option[ConsensusTrigger] => F[Unit],
    processStateUpdate: Key => F[Unit],
    logger: Logger[F]
  )(implicit S: Supervisor[F]): F[ConsensusQueue[F, Key]] =
    for {
      operationQueue <- Queue.unbounded[F, ConsensusOperation[Key]]
      pendingUpdates <- Ref.of[F, Set[Key]](Set.empty)

      queue = new ConsensusQueueImpl[F, Key](
        operationQueue,
        pendingUpdates,
        processFacilitation,
        processStateUpdate,
        logger
      )

      _ <- S.supervise(queue.runProcessor)

    } yield queue

  private class ConsensusQueueImpl[F[_]: Async, Key: Order: Show](
    operationQueue: Queue[F, ConsensusOperation[Key]],
    pendingUpdates: Ref[F, Set[Key]],
    processFacilitation: Option[ConsensusTrigger] => F[Unit],
    processStateUpdate: Key => F[Unit],
    logger: Logger[F]
  ) extends ConsensusQueue[F, Key] {

    override def requestFacilitation(trigger: Option[ConsensusTrigger]): F[Unit] = {
      val operation = ConsensusOperation.FacilitateRound[Key](trigger)

      operationQueue.offer(operation)
    }

    override def requestStateUpdate(key: Key): F[Unit] =
      pendingUpdates.modify { pending =>
        if (pending.contains(key)) {
          (pending, true)
        } else {
          (pending + key, true)
        }
      }.flatMap { _ =>
        operationQueue.offer(ConsensusOperation.UpdateState(key))
      }

    override def clearPendingUpdate(key: Key): F[Unit] =
      pendingUpdates.update(_ - key)

    def runProcessor: F[Unit] =
      operationQueue.take.flatMap {
        case ConsensusOperation.FacilitateRound(trigger) =>
          processFacilitation(trigger).handleErrorWith { err =>
            logger.error(err)(s"Error processing facilitation: trigger=${trigger.show}")
          }

        case ConsensusOperation.UpdateState(key) =>
          processStateUpdate(key).handleErrorWith { err =>
            logger.error(err)(s"Error processing state update: key=${key.show}") >>
              pendingUpdates.update(_ - key)
          }
      } >> runProcessor
  }
}
