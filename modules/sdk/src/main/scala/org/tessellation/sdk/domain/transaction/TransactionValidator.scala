package org.tessellation.sdk.domain.transaction

import cats.data.ValidatedNec
import cats.effect.Async
import cats.syntax.all._

import org.tessellation.ext.cats.syntax.validated._
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction.Transaction
import org.tessellation.sdk.domain.transaction.TransactionValidator.TransactionValidationErrorOr
import org.tessellation.security.signature.SignedValidator.SignedValidationError
import org.tessellation.security.signature.{Signed, SignedValidator}

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._

trait TransactionValidator[F[_], T <: Transaction] {

  def validate(signedTransaction: Signed[T]): F[TransactionValidationErrorOr[Signed[T]]]

}

object TransactionValidator {

  def make[F[_]: Async, T <: Transaction](
    signedValidator: SignedValidator[F]
  ): TransactionValidator[F, T] =
    new TransactionValidator[F, T] {

      def validate(
        signedTransaction: Signed[T]
      ): F[TransactionValidationErrorOr[Signed[T]]] =
        for {
          signaturesV <- signedValidator
            .validateSignatures(signedTransaction)
            .map(_.errorMap[TransactionValidationError](InvalidSigned))
          srcAddressSignatureV <- validateSourceAddressSignature(signedTransaction)
          differentSrcAndDstV = validateDifferentSourceAndDestinationAddress(signedTransaction)
        } yield
          signaturesV
            .productR(srcAddressSignatureV)
            .productR(differentSrcAndDstV)

      private def validateSourceAddressSignature(
        signedTx: Signed[T]
      ): F[TransactionValidationErrorOr[Signed[T]]] =
        signedValidator
          .isSignedExclusivelyBy(signedTx, signedTx.source)
          .map(_.errorMap[TransactionValidationError](_ => NotSignedBySourceAddressOwner))

      private def validateDifferentSourceAndDestinationAddress(
        signedTx: Signed[T]
      ): TransactionValidationErrorOr[Signed[T]] =
        if (signedTx.source =!= signedTx.destination)
          signedTx.validNec[TransactionValidationError]
        else
          SameSourceAndDestinationAddress(signedTx.source).invalidNec[Signed[T]]
    }

  @derive(eqv, show)
  sealed trait TransactionValidationError
  case class InvalidSigned(error: SignedValidationError) extends TransactionValidationError
  case object NotSignedBySourceAddressOwner extends TransactionValidationError
  case class SameSourceAndDestinationAddress(address: Address) extends TransactionValidationError

  type TransactionValidationErrorOr[A] = ValidatedNec[TransactionValidationError, A]
}
