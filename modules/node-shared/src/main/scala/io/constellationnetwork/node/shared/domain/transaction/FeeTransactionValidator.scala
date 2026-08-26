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
import io.constellationnetwork.security.signature.SignedValidator.SignedValidationError
import io.constellationnetwork.security.signature.{Signed, SignedValidator}

import derevo.cats.{eqv, show}
import derevo.derive

trait FeeTransactionValidator[F[_]] {
  def validate(
    signedTransaction: Signed[FeeTransaction],
    verifySignatures: Boolean
  ): F[FeeTransactionValidationErrorOr[Signed[FeeTransaction]]]
  def validate(
    signedTransactions: NonEmptyList[Signed[FeeTransaction]],
    verifySignatures: Boolean
  ): F[FeeTransactionValidationErrorOr[NonEmptyList[Signed[FeeTransaction]]]]
}

object FeeTransactionValidator {
  def make[F[_]: Async: JsonSerializer: SecurityProvider](
    signedValidator: SignedValidator[F]
  ): FeeTransactionValidator[F] =
    new FeeTransactionValidator[F] {

      // verifySignatures follows the fixing-data-application-fee-validation ordinal. Below it the earlier rule
      // is replayed unchanged, because signed history was produced under that rule and re-running it has to
      // give the same artifact.
      def validate(
        signedTransaction: Signed[FeeTransaction],
        verifySignatures: Boolean
      ): F[FeeTransactionValidationErrorOr[Signed[FeeTransaction]]] =
        for {
          srcAddressSignatureV <-
            if (verifySignatures) validateSourceWalletAuthorization(signedTransaction)
            else validateSourceAddressSignature(signedTransaction)
          differentSrcAndDstV = validateDifferentSourceAndDestinationAddress(signedTransaction)
        } yield
          srcAddressSignatureV
            .productR(differentSrcAndDstV)

      def validate(
        signedTransactions: NonEmptyList[Signed[FeeTransaction]],
        verifySignatures: Boolean
      ): F[FeeTransactionValidationErrorOr[NonEmptyList[Signed[FeeTransaction]]]] =
        signedTransactions
          .traverse(validate(_, verifySignatures))
          .map(_.sequence)

      // Both rules apply here: every proof verifies against the transaction's own serialized bytes, and every
      // proof belongs to the source wallet. The data application layer applies the same pair at or above the
      // same ordinal, so the two layers reach the same verdict. The proof check runs first because it caps
      // proof count, which bounds the work the address check then does over the same proofs.
      private def validateSourceWalletAuthorization(
        signedTx: Signed[FeeTransaction]
      ): F[FeeTransactionValidationErrorOr[Signed[FeeTransaction]]] =
        FeeTransactionSignatureValidator
          .validate(signedTx)
          .map(_.errorMap[FeeTransactionValidationError](InvalidFeeTransactionSignature))
          .flatMap { signatureV =>
            if (signatureV.isValid) validateSourceAddressSignature(signedTx)
            else signatureV.pure[F]
          }

      // Derives an address from each proof id and compares it to the source. Establishes which wallet each
      // proof names, and nothing beyond that.
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
  case class InvalidFeeTransactionSignature(error: FeeTransactionSignatureValidationError) extends FeeTransactionValidationError
  case object NotSignedBySourceAddressOwner extends FeeTransactionValidationError
  case class SameSourceAndDestinationAddress(address: Address) extends FeeTransactionValidationError

  type FeeTransactionValidationErrorOr[A] = ValidatedNec[FeeTransactionValidationError, A]
}
