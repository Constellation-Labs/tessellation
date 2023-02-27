package org.tessellation.sdk.domain.transaction

import cats.data.ValidatedNec
import cats.effect.Async
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.order._
import cats.syntax.validated._

import org.tessellation.ext.cats.syntax.validated._
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction.{Transaction, TransactionOrdinal, TransactionReference}
import org.tessellation.sdk.domain.transaction.TransactionValidator.TransactionValidationError
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._

trait ContextualTransactionValidator[F[_], T <: Transaction] {

  import ContextualTransactionValidator._

  def validate(
    signedTransaction: Signed[T]
  ): F[ContextualTransactionValidationErrorOr[Signed[T]]]

}

object ContextualTransactionValidator {

  trait TransactionValidationContext[F[_]] {

    def getLastTransactionRef(address: Address): F[TransactionReference]

  }

  def make[F[_]: Async, T <: Transaction](
    nonContextualValidator: TransactionValidator[F, T],
    context: TransactionValidationContext[F]
  ): ContextualTransactionValidator[F, T] =
    new ContextualTransactionValidator[F, T] {

      def validate(
        signedTransaction: Signed[T]
      ): F[ContextualTransactionValidationErrorOr[Signed[T]]] =
        for {
          nonContextuallyV <- validateNonContextually(signedTransaction)
          lastTxRefV <- validateLastTransactionRef(signedTransaction)
        } yield
          nonContextuallyV
            .productR(lastTxRefV)

      private def validateNonContextually(
        signedTx: Signed[T]
      ): F[ContextualTransactionValidationErrorOr[Signed[T]]] =
        nonContextualValidator
          .validate(signedTx)
          .map(_.errorMap(NonContextualValidationError))

      private def validateLastTransactionRef(
        signedTx: Signed[T]
      ): F[ContextualTransactionValidationErrorOr[Signed[T]]] =
        context.getLastTransactionRef(signedTx.source).map { lastTxRef =>
          if (signedTx.parent.ordinal > lastTxRef.ordinal)
            signedTx.validNec[ContextualTransactionValidationError]
          else if (signedTx.parent.ordinal < lastTxRef.ordinal)
            ParentOrdinalLowerThenLastTxOrdinal(signedTx.parent.ordinal, lastTxRef.ordinal).invalidNec
          else {
            if (signedTx.parent.hash =!= lastTxRef.hash)
              ParentHashDifferentThanLastTxHash(signedTx.parent.hash, lastTxRef.hash).invalidNec
            else
              signedTx.validNec[ContextualTransactionValidationError]
          }
        }
    }

  @derive(eqv, show)
  sealed trait ContextualTransactionValidationError
  case class ParentOrdinalLowerThenLastTxOrdinal(parentOrdinal: TransactionOrdinal, lastTxOrdinal: TransactionOrdinal)
      extends ContextualTransactionValidationError
  case class ParentHashDifferentThanLastTxHash(parentHash: Hash, lastTxHash: Hash) extends ContextualTransactionValidationError
  case class NonContextualValidationError(error: TransactionValidationError) extends ContextualTransactionValidationError

  type ContextualTransactionValidationErrorOr[A] = ValidatedNec[ContextualTransactionValidationError, A]
}
