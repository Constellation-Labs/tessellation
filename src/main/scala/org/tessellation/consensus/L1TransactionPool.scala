package org.tessellation.consensus

import cats.effect.IO
import cats.effect.concurrent.Ref
import cats.syntax.all._
import io.chrisdavenport.fuuid.FUUID

object L1TransactionPool {

  def init(txs: Set[L1Transaction]): IO[L1TransactionPoolEnqueue] =
    for {
      ref <- Ref.of[IO, Set[L1Transaction]](Set.empty)
      txPool <- L1TransactionPool.apply(ref)
      _ <- txs.toList.traverse(txPool.enqueue)
    } yield txPool

  def apply(ref: Ref[IO, Set[L1Transaction]]): IO[L1TransactionPoolEnqueue] =
    IO.delay {
      new L1TransactionPoolEnqueue {
        private val pulledTxs: Ref[IO, Map[FUUID, Set[L1Transaction]]] =
          Ref.unsafe(Map.empty[FUUID, Set[L1Transaction]])

        def enqueue(tx: L1Transaction): IO[Unit] =
          ref.modify(txs => (txs + tx, ()))

        def dequeue(n: Int): IO[Set[L1Transaction]] =
          ref.modify { txs =>
            val taken: Set[L1Transaction] = txs.take(n)

            (txs -- taken, taken)
          }

        def pull(roundId: FUUID, n: Int): IO[Set[L1Transaction]] =
          pulledTxs.get
            .map(_.get(roundId))
            .flatMap {
              case Some(txs) => IO.pure(txs)
              case None =>
                for {
                  txs <- dequeue(n)
                  _ <- addToPulled(roundId, txs)
                } yield txs
            }

        private def addToPulled(roundId: FUUID, txs: Set[L1Transaction]): IO[Unit] =
          pulledTxs.modify(m => (m.updated(roundId, txs), ()))

        private def removeFromPulled(roundId: FUUID): IO[Set[L1Transaction]] =
          pulledTxs.modify(m => {
            val pulled = m.getOrElse(roundId, Set.empty[L1Transaction])
            (m.removed(roundId), pulled)
          })

        private def reenqueue(roundId: FUUID): IO[Unit] =
          for {
            removedTxs <- removeFromPulled(roundId)
            _ <- removedTxs.toList.traverse(enqueue)
          } yield ()
      }
    }

  trait L1TransactionPoolEnqueue {
    def enqueue(tx: L1Transaction): IO[Unit]

    def dequeue(n: Int): IO[Set[L1Transaction]]

    def pull(roundId: FUUID, n: Int): IO[Set[L1Transaction]]
  }

}
