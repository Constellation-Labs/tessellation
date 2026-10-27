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
    signerPolicy: FeeTransactionSignerPolicy
  ): F[FeeTransactionValidationErrorOr[Signed[FeeTransaction]]]
  def validate(
    signedTransactions: NonEmptyList[Signed[FeeTransaction]],
    signerPolicy: FeeTransactionSignerPolicy
  ): F[FeeTransactionValidationErrorOr[NonEmptyList[Signed[FeeTransaction]]]]
}

/** Which proof rule acceptance applies to a fee transaction, selected from the parent global ordinal. */
sealed trait FeeTransactionSignerPolicy

object FeeTransactionSignerPolicy {

  /** Below fixing-data-application-fee-validation: every proof id must map to the source address; proof bytes are not verified. */
  case object LegacyExclusiveSource extends FeeTransactionSignerPolicy

  /** From fixing-data-application-fee-validation (mainnet 6818000, release/mainnet #1577) until fee-transaction-security: every proof is
    * verified against the transaction bytes first, then every proof must belong to the source wallet.
    */
  case object VerifiedExclusiveSource extends FeeTransactionSignerPolicy

  /** From fee-transaction-security: every proof is verified and the source must sign; source-authorized co-signers are allowed. */
  case object VerifiedSourceAuthorized extends FeeTransactionSignerPolicy

  def select(walletAuthorizationActive: Boolean, proofVerificationActive: Boolean): FeeTransactionSignerPolicy =
    if (walletAuthorizationActive) VerifiedSourceAuthorized
    else if (proofVerificationActive) VerifiedExclusiveSource
    else LegacyExclusiveSource
}

object FeeTransactionValidator {
  def make[F[_]: Async: JsonSerializer: SecurityProvider](
    signedValidator: SignedValidator[F]
  ): FeeTransactionValidator[F] =
    new FeeTransactionValidator[F] {
      def validate(
        signedTransaction: Signed[FeeTransaction],
        signerPolicy: FeeTransactionSignerPolicy
      ): F[FeeTransactionValidationErrorOr[Signed[FeeTransaction]]] =
        for {
          srcAddressSignatureV <- signerPolicy match {
            case FeeTransactionSignerPolicy.LegacyExclusiveSource =>
              validateSourceAddressSignature(signedTransaction)
            case FeeTransactionSignerPolicy.VerifiedSourceAuthorized =>
              validateProofs(signedTransaction)
            case FeeTransactionSignerPolicy.VerifiedExclusiveSource =>
              // Proof verification runs first, as on release/mainnet: it caps the proof count and never raises on an
              // unparseable id, so the exclusivity check only ever sees proofs that verified.
              validateProofs(signedTransaction).flatMap { proofsV =>
                if (proofsV.isValid) validateSourceAddressSignature(signedTransaction) else proofsV.pure[F]
              }
          }
          differentSrcAndDstV = validateDifferentSourceAndDestinationAddress(signedTransaction)
        } yield
          srcAddressSignatureV
            .productR(differentSrcAndDstV)

      def validate(
        signedTransactions: NonEmptyList[Signed[FeeTransaction]],
        signerPolicy: FeeTransactionSignerPolicy
      ): F[FeeTransactionValidationErrorOr[NonEmptyList[Signed[FeeTransaction]]]] =
        signedTransactions
          .traverse(validate(_, signerPolicy))
          .map(_.sequence)

      private def validateProofs(
        signedTx: Signed[FeeTransaction]
      ): F[FeeTransactionValidationErrorOr[Signed[FeeTransaction]]] =
        FeeTransactionSignatureValidator
          .validate(signedTx)
          .map(_.errorMap[FeeTransactionValidationError](InvalidSigned))

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
