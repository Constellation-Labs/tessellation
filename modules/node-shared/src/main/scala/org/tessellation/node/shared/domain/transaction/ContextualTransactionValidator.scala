package org.tessellation.node.shared.domain.transaction

import cats.data.{Validated, ValidatedNec}
import cats.effect.Async
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.order._
import cats.syntax.validated._

import org.tessellation.ext.cats.syntax.validated._
import org.tessellation.node.shared.domain.transaction.TransactionValidator.TransactionValidationError
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction.{Transaction, TransactionOrdinal, TransactionReference}
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

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

  private val lockedAddresses = Set(
    Address("DAG6LvxLSdWoC9uJZPgXtcmkcWBaGYypF6smaPyH") // NOTE: BitForex
  )

  def make[F[_]: Async](
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
          notLocked = validateNotLocked(signedTransaction)
        } yield notLocked *> nonContextuallyV *> lastTxRefV

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

      private def validateNotLocked(
        signedTx: Signed[Transaction]
      ): ContextualTransactionValidationErrorOr[Signed[Transaction]] =
        Validated
          .condNec[ContextualTransactionValidationError, Signed[Transaction]](
            !lockedAddresses.contains(signedTx.source),
            signedTx,
            LockedAddressError(signedTx.source)
          )
    }

  @derive(eqv, show)
  sealed trait ContextualTransactionValidationError
  case class ParentOrdinalLowerThenLastTxOrdinal(parentOrdinal: TransactionOrdinal, lastTxOrdinal: TransactionOrdinal)
      extends ContextualTransactionValidationError
  case class ParentHashDifferentThanLastTxHash(parentHash: Hash, lastTxHash: Hash) extends ContextualTransactionValidationError
  case class NonContextualValidationError(error: TransactionValidationError) extends ContextualTransactionValidationError
  case class LockedAddressError(address: Address) extends ContextualTransactionValidationError

  type ContextualTransactionValidationErrorOr[A] = ValidatedNec[ContextualTransactionValidationError, A]
}
