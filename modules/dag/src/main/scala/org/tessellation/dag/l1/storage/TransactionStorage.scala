package org.tessellation.dag.l1.storage

import cats.Monad
import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.{Ref, Sync}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.collection.immutable.SortedSet

import org.tessellation.dag.l1.DAGBlockValidator.takeConsecutiveTransactions
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction.{Transaction, TransactionReference}
import org.tessellation.security.Hashed
import org.tessellation.security.signature.Signed

trait TransactionStorage[F[_]] {
  def isParentAccepted(tx: Transaction): F[Boolean]

  //TODO: maybe we should just keep the last transaction so that we can derive reference from the full transaction
  // (I guess it would) need to be Hashed[Transaction]
  def getLastAcceptedTransactionRef(address: Address): F[TransactionReference]

  def acceptTransaction(hashedTx: Hashed[Transaction]): F[Unit]

  def putTransactions(transactions: Set[Signed[Transaction]]): F[Unit]

  def pullTransactions(): F[Option[NonEmptyList[Signed[Transaction]]]]
}

object TransactionStorage {
  import org.tessellation.dag.l1.DAGStateChannel.{ordinalNumberOrder, ordinalNumberOrdering}

  def make[F[_]: Sync]: TransactionStorage[F] =
    make(
      Ref.unsafe(Map.empty),
      Ref.unsafe(Map.empty)
    )

  // TODO: maybe we should store just last transactions, not the references
  def make[F[_]: Monad](
    lastAccepted: Ref[F, Map[Address, TransactionReference]],
    waitingTransactions: Ref[F, Map[Address, NonEmptySet[Signed[Transaction]]]]
  ): TransactionStorage[F] =
    new TransactionStorage[F] {

      def isParentAccepted(transaction: Transaction): F[Boolean] =
        lastAccepted.get.map { accepted =>
          lazy val isVeryFirstTransaction = transaction.parent == TransactionReference.empty
          lazy val isParentHashAccepted = accepted.get(transaction.source).contains(transaction.parent)

          isVeryFirstTransaction || isParentHashAccepted
        }

      def getLastAcceptedTransactionRef(source: Address): F[TransactionReference] =
        lastAccepted.get.map(_.getOrElse(source, TransactionReference.empty))

      def acceptTransaction(hashedTx: Hashed[Transaction]): F[Unit] =
        lastAccepted.modify { current =>
          val source = hashedTx.signed.value.source
          val reference = TransactionReference(hashedTx.hash, hashedTx.signed.value.ordinal)

          (current + (source -> reference), ())
        }

      // TODO: check again! consider using MapRef for transactions
      def putTransactions(transactions: Set[Signed[Transaction]]): F[Unit] =
        transactions.toList
          .traverse(
            tx =>
              waitingTransactions.modify { current =>
                val updated = current
                  .get(tx.value.source)
                  .map(txs => NonEmptySet.of(tx, txs.toNonEmptyList.toList: _*))
                  .getOrElse(NonEmptySet.one(tx))

                (current.updated(tx.value.source, updated), ())
              }
          )
          .void

      // TODO: I would like to pull inside of a modify so I'm prefetching the whole lastAccepted (which could theoretically change during the pull which increases invalid block creation probablity - though I'm not sure I should worry about it)
      def pullTransactions(): F[Option[NonEmptyList[Signed[Transaction]]]] =
        for {
          lastAccepted <- lastAccepted.get
          pulled <- waitingTransactions.modify { waiting =>
            waiting
              .foldRight((waiting, List.empty[Signed[Transaction]])) { // TODO: wouldn't starting with empty map and inserting to a map be better then update/remove/leave as is flow
                case ((address, awaiting), (stillWaiting, pulled)) =>
                  val lastTx = lastAccepted.getOrElse(address, TransactionReference.empty)
                  val pulledForAddress = takeConsecutiveTransactions(lastTx, awaiting.toNonEmptyList.toList)
                  val updatedStillWaiting =
                    NonEmptySet.fromSet(SortedSet.from(pulledForAddress)) match {
                      case Some(pulledTxs) =>
                        NonEmptySet.fromSet(awaiting.diff(pulledTxs)) match {
                          case Some(remainingAwaiting) => stillWaiting.updated(address, remainingAwaiting)
                          case None                    => stillWaiting.removed(address)
                        }
                      case None => stillWaiting
                    }
                  (updatedStillWaiting, pulled ++ pulledForAddress)
              }
          }
        } yield NonEmptyList.fromList(pulled)

    }
}
