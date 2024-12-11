package io.constellationnetwork.node.shared.domain.transaction

import cats.data.ValidatedNec
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.ext.cats.syntax.validated._
import io.constellationnetwork.node.shared.config.types.AddressesConfig
import io.constellationnetwork.node.shared.domain.transaction.TransactionValidator.TransactionValidationErrorOr
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.transaction.Transaction
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.SignedValidator.SignedValidationError
import io.constellationnetwork.security.signature.{Signed, SignedValidator}

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.encoder
import derevo.derive
import eu.timepit.refined.auto._

trait TransactionValidator[F[_]] {

  def validate(signedTransaction: Signed[Transaction]): F[TransactionValidationErrorOr[Signed[Transaction]]]

}

object TransactionValidator {

  def make[F[_]: Async](cfg: AddressesConfig, signedValidator: SignedValidator[F], txHasher: Hasher[F]): TransactionValidator[F] =
    new TransactionValidator[F] {
      def validate(
        signedTransaction: Signed[Transaction]
      ): F[TransactionValidationErrorOr[Signed[Transaction]]] = {
        implicit val hasher = txHasher

        for {
          signaturesV <- signedValidator
            .validateSignatures(signedTransaction)
            .map(_.errorMap[TransactionValidationError](InvalidSigned))
          srcAddressSignatureV <- validateSourceAddressSignature(signedTransaction)
          differentSrcAndDstV = validateDifferentSourceAndDestinationAddress(signedTransaction)
          addressNotLockedV = validateAddressIsNotLocked(signedTransaction)
        } yield
          signaturesV
            .productR(srcAddressSignatureV)
            .productR(differentSrcAndDstV)
            .productR(addressNotLockedV)
      }

      private def validateSourceAddressSignature(
        signedTx: Signed[Transaction]
      ): F[TransactionValidationErrorOr[Signed[Transaction]]] =
        signedValidator
          .isSignedExclusivelyBy(signedTx, signedTx.source)
          .map(_.errorMap[TransactionValidationError](_ => NotSignedBySourceAddressOwner))

      private def validateDifferentSourceAndDestinationAddress(
        signedTx: Signed[Transaction]
      ): TransactionValidationErrorOr[Signed[Transaction]] =
        if (signedTx.source =!= signedTx.destination)
          signedTx.validNec[TransactionValidationError]
        else
          SameSourceAndDestinationAddress(signedTx.source).invalidNec[Signed[Transaction]]

      private def validateAddressIsNotLocked(signedTx: Signed[Transaction]): TransactionValidationErrorOr[Signed[Transaction]] =
        if (lockedAddresses.contains(signedTx.value.source))
          AddressLocked(signedTx.value.source).invalidNec[Signed[Transaction]]
        else
          signedTx.validNec[TransactionValidationError]

      private val lockedAddresses = cfg.locked

    }

  @derive(eqv, show, encoder)
  sealed trait TransactionValidationError
  case class InvalidSigned(error: SignedValidationError) extends TransactionValidationError
  case object NotSignedBySourceAddressOwner extends TransactionValidationError
  case class SameSourceAndDestinationAddress(address: Address) extends TransactionValidationError
  case class AddressLocked(address: Address) extends TransactionValidationError

  type TransactionValidationErrorOr[A] = ValidatedNec[TransactionValidationError, A]
}
