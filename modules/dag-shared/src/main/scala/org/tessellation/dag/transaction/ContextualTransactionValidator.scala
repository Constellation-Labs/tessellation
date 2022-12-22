package org.tessellation.dag.transaction

import cats.data.ValidatedNec
import cats.effect.Async
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.order._
import cats.syntax.validated._

import org.tessellation.dag.transaction.TransactionValidator.TransactionValidationError
import org.tessellation.ext.cats.syntax.validated._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.security.hash.Hash
import org.tessellation.schema.security.signature.Signed
import org.tessellation.schema.transaction.{Transaction, TransactionOrdinal, TransactionReference}

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._

trait ContextualTransactionValidator[F[_]] {

  import ContextualTransactionValidator._

  def validate(
    signedTransaction: Signed[Transaction]
  ): F[ContextualTransactionValidationErrorOr[Signed[Transaction]]]

}

object ContextualTransactionValidator {

  trait TransactionValidationContext[F[_]] {

    def getLastTransactionRef(address: Address): F[TransactionReference]

  }

  def make[F[_]: Async: KryoSerializer](
    nonContextualValidator: TransactionValidator[F],
    context: TransactionValidationContext[F]
  ): ContextualTransactionValidator[F] =
    new ContextualTransactionValidator[F] {

      def validate(
        signedTransaction: Signed[Transaction]
      ): F[ContextualTransactionValidationErrorOr[Signed[Transaction]]] =
        for {
          nonContextuallyV <- validateNonContextually(signedTransaction)
          lastTxRefV <- validateLastTransactionRef(signedTransaction)
        } yield
          nonContextuallyV
            .productR(lastTxRefV)

      private def validateNonContextually(
        signedTx: Signed[Transaction]
      ): F[ContextualTransactionValidationErrorOr[Signed[Transaction]]] =
        nonContextualValidator
          .validate(signedTx)
          .map(_.errorMap(NonContextualValidationError))

      private def validateLastTransactionRef(
        signedTx: Signed[Transaction]
      ): F[ContextualTransactionValidationErrorOr[Signed[Transaction]]] =
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
