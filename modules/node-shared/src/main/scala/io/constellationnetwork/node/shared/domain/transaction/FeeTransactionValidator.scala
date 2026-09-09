package io.constellationnetwork.node.shared.domain.transaction

import cats.data.{NonEmptyList, ValidatedNec}
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.FeeTransaction
import io.constellationnetwork.currency.validations.FeeTransactionSignatureValidator
import io.constellationnetwork.currency.validations.FeeTransactionSignatureValidator.FeeTransactionSignatureValidationError
import io.constellationnetwork.ext.cats.syntax.validated._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.domain.transaction.FeeTransactionValidator.FeeTransactionValidationErrorOr
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.{Signed, SignedValidator}

trait FeeTransactionValidator[F[_]] {
  def validate(
    signedTransaction: Signed[FeeTransaction],
    enforceWalletAuthorization: Boolean
  ): F[FeeTransactionValidationErrorOr[Signed[FeeTransaction]]]
  def validate(
    signedTransactions: NonEmptyList[Signed[FeeTransaction]],
    enforceWalletAuthorization: Boolean
  ): F[FeeTransactionValidationErrorOr[NonEmptyList[Signed[FeeTransaction]]]]
}

object FeeTransactionValidator {
  def make[F[_]: Async: JsonSerializer: SecurityProvider](
    signedValidator: SignedValidator[F]
  ): FeeTransactionValidator[F] =
    new FeeTransactionValidator[F] {
      def validate(
        signedTransaction: Signed[FeeTransaction],
        enforceWalletAuthorization: Boolean
      ): F[FeeTransactionValidationErrorOr[Signed[FeeTransaction]]] =
        for {
          srcAddressSignatureV <-
            if (enforceWalletAuthorization)
              FeeTransactionSignatureValidator
                .validate(signedTransaction)
                .map(_.errorMap[FeeTransactionValidationError](InvalidSigned))
            else
              validateSourceAddressSignature(signedTransaction)
          differentSrcAndDstV = validateDifferentSourceAndDestinationAddress(signedTransaction)
        } yield
          srcAddressSignatureV
            .productR(differentSrcAndDstV)

      def validate(
        signedTransactions: NonEmptyList[Signed[FeeTransaction]],
        enforceWalletAuthorization: Boolean
      ): F[FeeTransactionValidationErrorOr[NonEmptyList[Signed[FeeTransaction]]]] =
        signedTransactions
          .traverse(validate(_, enforceWalletAuthorization))
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

  sealed trait FeeTransactionValidationError
  case class InvalidSigned(error: FeeTransactionSignatureValidationError) extends FeeTransactionValidationError
  case object NotSignedBySourceAddressOwner extends FeeTransactionValidationError
  case class SameSourceAndDestinationAddress(address: Address) extends FeeTransactionValidationError

  type FeeTransactionValidationErrorOr[A] = ValidatedNec[FeeTransactionValidationError, A]
}
