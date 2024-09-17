package org.tessellation.node.shared.domain.transaction

import cats.data.{NonEmptyList, ValidatedNec}
import cats.effect.Async
import cats.syntax.all._

import org.tessellation.currency.dataApplication.FeeTransaction
import org.tessellation.ext.cats.syntax.validated._
import org.tessellation.node.shared.domain.transaction.FeeTransactionValidator.FeeTransactionValidationErrorOr
import org.tessellation.schema.address.Address
import org.tessellation.security.signature.SignedValidator.SignedValidationError
import org.tessellation.security.signature.{Signed, SignedValidator}

import derevo.cats.{eqv, show}
import derevo.derive

trait FeeTransactionValidator[F[_]] {
  def validate(signedTransaction: Signed[FeeTransaction]): F[FeeTransactionValidationErrorOr[Signed[FeeTransaction]]]
  def validate(
    signedTransactions: NonEmptyList[Signed[FeeTransaction]]
  ): F[FeeTransactionValidationErrorOr[NonEmptyList[Signed[FeeTransaction]]]]
}

object FeeTransactionValidator {
  def make[F[_]: Async](
    signedValidator: SignedValidator[F]
  ): FeeTransactionValidator[F] =
    new FeeTransactionValidator[F] {
      def validate(
        signedTransaction: Signed[FeeTransaction]
      ): F[FeeTransactionValidationErrorOr[Signed[FeeTransaction]]] =
        for {
          srcAddressSignatureV <- validateSourceAddressSignature(signedTransaction)
          differentSrcAndDstV = validateDifferentSourceAndDestinationAddress(signedTransaction)
        } yield
          srcAddressSignatureV
            .productR(differentSrcAndDstV)

      def validate(
        signedTransactions: NonEmptyList[Signed[FeeTransaction]]
      ): F[FeeTransactionValidationErrorOr[NonEmptyList[Signed[FeeTransaction]]]] =
        signedTransactions
          .traverse(validate)
          .map(_.sequence)

      private def validateSourceAddressSignature(
        signedTx: Signed[FeeTransaction]
      ): F[FeeTransactionValidationErrorOr[Signed[FeeTransaction]]] =
        signedValidator
          .isSignedExclusivelyBy(signedTx, signedTx.source)
          .map(_.errorMap[FeeTransactionValidationError](_ => NotSignedBySourceAddressOwner))

      private def validateDifferentSourceAndDestinationAddress(
        signedTx: Signed[FeeTransaction]
      ): FeeTransactionValidationErrorOr[Signed[FeeTransaction]] =
        if (signedTx.source =!= signedTx.destination)
          signedTx.validNec[FeeTransactionValidationError]
        else
          SameSourceAndDestinationAddress(signedTx.source).invalidNec[Signed[FeeTransaction]]
    }

  @derive(eqv, show)
  sealed trait FeeTransactionValidationError
  case class InvalidSigned(error: SignedValidationError) extends FeeTransactionValidationError
  case object NotSignedBySourceAddressOwner extends FeeTransactionValidationError
  case class SameSourceAndDestinationAddress(address: Address) extends FeeTransactionValidationError

  type FeeTransactionValidationErrorOr[A] = ValidatedNec[FeeTransactionValidationError, A]
}
