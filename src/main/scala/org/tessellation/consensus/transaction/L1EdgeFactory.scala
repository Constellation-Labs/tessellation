package org.tessellation.consensus.transaction

import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, IO}
import cats.implicits._
import fs2._
import fs2.concurrent.Queue
import org.tessellation.Log
import org.tessellation.consensus.{L1Edge, L1Transaction}

import scala.collection.immutable.{SortedSet, TreeSet}

class L1EdgeFactory()(implicit O: Ordering[L1Transaction]) {
  type Address = String
  type TransactionHash = String

  private val lastAccepted: Ref[IO, Map[Address, TransactionHash]] = Ref.unsafe(Map.empty)
  private val waitingTransactions: Ref[IO, Map[Address, TreeSet[L1Transaction]]] = Ref.unsafe(Map.empty)
  private val readyTransactions: Ref[IO, Map[Address, TreeSet[L1Transaction]]] = Ref.unsafe(Map.empty)
  private val transactionsPerEdge = 5

  def createEdges: Pipe[IO, L1Transaction, L1Edge[L1Transaction]] = { a =>
    val x = a.evalMap { incomingTransaction =>
      isParentAccepted(incomingTransaction)
        .ifM(
          IO(Log.red(s"[Forward to the Edge] ${incomingTransaction}")) >> dequeue1ReadyTransactions()
            .map(_ + incomingTransaction)
            .map(L1Edge[L1Transaction](_)),
          IO(Log.red(s"[Put on the WaitingPool] ${incomingTransaction}")) >> wait(incomingTransaction) >> IO(
            L1Edge[L1Transaction](TreeSet.empty)
          )
        )
    }

    x.filter(_.txs.nonEmpty).evalTap(edge => IO(Log.red(s"[Created Edge] ${edge}")))
  }

  private def wait(transaction: L1Transaction): IO[Unit] =
    waitingTransactions.modify { pool =>
      val updated = pool.updatedWith(transaction.src) { waitingChain =>
        waitingChain
          .map(_ ++ TreeSet(transaction))
          .orElse(Some(TreeSet(transaction)))
      }
      (updated, ())
    }

  private def isParentAccepted(transaction: L1Transaction): IO[Boolean] =
    lastAccepted.get.map { accepted =>
      lazy val isVeryFirstTransaction = transaction.parentHash == ""
      lazy val isParentHashAccepted = accepted.get(transaction.src).contains(transaction.parentHash)
      isVeryFirstTransaction || isParentHashAccepted
    }

  private def dequeue1ReadyTransactions(): IO[TreeSet[L1Transaction]] = readyTransactions.modify { ready =>
    ready
      .foldRight((ready, TreeSet.empty)) {
        case ((address, enqueued), (updatedReady, dequeued)) =>
          (updatedReady.updated(address, enqueued.drop(1)), dequeued + enqueued.head)
      }
  }

  def ready(acceptedTransaction: L1Transaction): IO[Unit] =
    for {
      // TODO: Check if tx exists in waitingTransactions first
      _ <- lastAccepted.modify { txs =>
        (txs.updated(acceptedTransaction.src, acceptedTransaction.hash), ())
      }

      unlockedTx <- waitingTransactions.modify { pool =>
        pool
          .get(acceptedTransaction.src)
          .flatMap(_.find(_.parentHash == acceptedTransaction.hash))
          .map { readyTx =>
            val updatedWaitingTransactions = pool.updatedWith(readyTx.src)(_.map(_.filterNot(_.hash == readyTx.hash)))
            (updatedWaitingTransactions, Some(readyTx))
          }
          .getOrElse(pool, None)
      }

      _ <- unlockedTx.fold(IO.unit) { tx =>
        readyTransactions.modify { readyPool =>
          (readyPool.updatedWith(tx.src)(_.map(_ + tx)), ())
        }
      }

      _ <- IO {
        Log.white(s"[Parent accepted] $acceptedTransaction")
      }

    } yield ()
}

object L1EdgeFactory {
  implicit val ordinalNumberOrdering: Ordering[L1Transaction] = (x: L1Transaction, y: L1Transaction) =>
    implicitly[Ordering[Int]].compare(x.ordinal, y.ordinal)

  def apply(): L1EdgeFactory = new L1EdgeFactory()(ordinalNumberOrdering)
}
