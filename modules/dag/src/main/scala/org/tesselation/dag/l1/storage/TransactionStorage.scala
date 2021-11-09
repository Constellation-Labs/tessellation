package org.tesselation.dag.l1.storage

import cats.Monad
import cats.effect.Ref
import cats.syntax.functor._

import org.tesselation.schema.address.Address
import org.tesselation.schema.transaction.{Transaction, TransactionReference}
import org.tesselation.security.Hashed

trait TransactionStorage[F[_]] {
  //def isAccepted(hash: Hash): F[Boolean]
  def isParentAccepted(tx: Transaction): F[Boolean]

  //TODO: maybe we should just keep the last transaction so that we can derive reference from the full transaction
  // (I guess it would) need to be Hashed[Transaction]
  def getLastAcceptedTransactionRef(address: Address): F[TransactionReference]

  def acceptTransaction(hashedTx: Hashed[Transaction]): F[Unit]
}

object TransactionStorage {

  // TODO: maybe we should store just last transactions, not the references
  def make[F[_]: Monad](lastAccepted: Ref[F, Map[Address, TransactionReference]]): TransactionStorage[F] =
    new TransactionStorage[F] {
      //def isAccepted(hash: Hash): F[Boolean] =

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
    }
}
