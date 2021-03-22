package org.tessellation.consensus.transaction

import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, IO}
import cats.implicits._
import fs2._
import fs2.concurrent.Queue
import org.tessellation.Log
import org.tessellation.consensus.{L1Edge, L1Transaction}

// TODO: Get rid of readyForAccept and use readyForAcceptGrouped instead
case class L1TransactionSorter(readyForAccept: Queue[IO, L1Transaction])(implicit C: Concurrent[IO]) {
  type Address = String
  type TransactionHash = String

  // We track lastAccepted transaction for Address to check if we can proceed with transaction or put to waitingPool instead
  private val lastAccepted: Ref[IO, Map[Address, TransactionHash]] = Ref.unsafe(Map.empty)
  // All the transactions without accepted parent wait here until parent gets accepted ("done" method). Transactions removed from waitingPool get enqueued in readyForAccept queue.
  private val waitingPool: Ref[IO, Map[Address, Seq[L1Transaction]]] = Ref.unsafe(Map.empty)
  private val readyForAcceptGrouped: Ref[IO, Map[Address, Seq[L1Transaction]]] = Ref.unsafe(Map.empty)

  def done(acceptedTransaction: L1Transaction): IO[Unit] =
    for {
      _ <- lastAccepted.modify { txs =>
        (txs.updated(acceptedTransaction.src, acceptedTransaction.hash), ())
      }

      toAccept <- waitingPool.modify { pool =>
        pool
          .get(acceptedTransaction.src)
          // TODO: TBC: Assuming that transactions in waitingPool are sorted topologically without gaps we just need to check
          //  if the first one can be accepted. If so then all the next ones can be accepted too so we remove these from waitingPool
          .filter(_.headOption.exists(_.parentHash == acceptedTransaction.hash))
          .map((pool.removed(acceptedTransaction.src), _))
          .getOrElse((pool, Seq.empty))
      }

      _ <- toAccept.toList.traverse(readyForAccept.enqueue1)
      _ <- readyForAcceptGrouped.modify { r =>
        (r.updatedWith(acceptedTransaction.src)(_.map(_ ++ toAccept).orElse(Some(toAccept))), ())
      }

    } yield ()

  //  def createEdge(incomingTransaction: L1Transaction): IO[Option[L1Edge[L1Transaction]]] =
  //    readyForAcceptGrouped.modify { r =>
  //      val edge = r
  //        .get(incomingTransaction.src)
  //        .map(_ :+ incomingTransaction)
  //        .filter(_.sliding(2).forall { case Seq(prev, curr) => curr.parentHash == prev.hash }) // TODO: Can we guarantee that?
  //        .map(txs => L1Edge(txs.toSet))
  //    }

  def optimize: Pipe[IO, L1Transaction, L1Transaction] =
    _.evalFilter { incomingTransaction =>
      // If transaction has parent accepted then we go to next step. Otherwise we put transaction to waiting list
      parentAccepted(incomingTransaction)
        .ifM(
          IO.pure(true),
          IO {
            Log.red(s"[WaitingPool] ${incomingTransaction}")
          } >> wait(incomingTransaction).as(false)
        )
    }.merge(readyForAccept.dequeue)

  def wait(transaction: L1Transaction): IO[Unit] =
    waitingPool.modify { pool =>
      val updated = pool.updatedWith(transaction.src) { waitingChain =>
        waitingChain
          .map(_ :+ transaction)
          .map(_.distinct)
          .orElse(Some(Seq(transaction))) // TODO: Topologically sort, maybe SortedSet with implicit Ordered[L1Transaction]?
      }
      (updated, ())
    }

  def parentAccepted(transaction: L1Transaction): IO[Boolean] =
    lastAccepted.modify { accepted =>
      lazy val isVeryFirstTransaction = transaction.parentHash == ""

      // TODO: We probably need to make sure that it is consistent across the cluster and avoid race conditions there.
      // TODO: Probably not needed
      lazy val isFirstTransactionOnThatNode = false

      lazy val isParentHashAccepted = accepted.get(transaction.src).contains(transaction.parentHash)

      (accepted, isVeryFirstTransaction || isFirstTransactionOnThatNode || isParentHashAccepted)
    }
}

object L1TransactionSorter {

  def apply()(implicit C: Concurrent[IO]): IO[L1TransactionSorter] =
    Queue.unbounded[IO, L1Transaction](C).map(L1TransactionSorter(_))
}
