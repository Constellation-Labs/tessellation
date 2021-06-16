package org.tessellation.consensus

import cats.effect.IO
import cats.effect.concurrent.Ref
import cats.implicits._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.tessellation.consensus.L1ConsensusStep.RoundId
import org.tessellation.consensus.transaction.RandomTransactionGenerator

import scala.collection.immutable.Queue

class L1CellCache(txGenerator: RandomTransactionGenerator) {
  private val assigned: Ref[IO, Map[RoundId, L1Cell]] = Ref.unsafe(Map.empty[RoundId, L1Cell])
  private val free: Ref[IO, Queue[L1Cell]] = Ref.unsafe(Queue.empty[L1Cell])
  private val logger = Slf4jLogger.getLogger[IO]

  def get(roundId: RoundId): IO[Option[L1Cell]] =
    for {
      assignedCells <- assigned.get
      freeCells <- free.get
      cell <- assignedCells.get(roundId) match {
        case Some(toReuse) =>
          logger.debug(s"Reusing cached cell for roundId $roundId.") >> Some(toReuse).pure[IO]
        case None =>
          freeCells.dequeueOption match {
            case Some((toAssign, queue)) =>
              logger.debug(s"Assigning free cell to roundId $roundId.") >> assign(toAssign, roundId) >> free.set(queue) >> Some(
                toAssign
              ).pure[IO]
            case None => generate(roundId).map(_.some)
          }
      }
    } yield cell

  def assign(cell: L1Cell, roundId: RoundId): IO[Unit] = assigned.modify(m => (m.updated(roundId, cell), ()))

  def cache(cell: L1Cell): IO[Unit] = free.modify(q => (q.enqueue(cell), ()))

  // TODO: Just for testing, remove.
  def generate(roundId: RoundId): IO[L1Cell] =
    for {
      _ <- logger.debug(s"Assigning generated cell for roundId $roundId.")
      cell <- txGenerator
        .generateRandomTransaction()
        .map(tx => L1Cell(L1Edge(Set(tx))))
      _ <- assign(cell, roundId)
    } yield cell
}

object L1CellCache {
  def apply(txGenerator: RandomTransactionGenerator): L1CellCache = new L1CellCache(txGenerator)
}
