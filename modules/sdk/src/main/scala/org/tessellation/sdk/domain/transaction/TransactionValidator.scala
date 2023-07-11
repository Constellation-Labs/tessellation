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
  val stardustPrimary: Address = Address("DAGSTARDUSTCOLLECTIVEHZOIPHXZUBFGNXWJETZVSPAPAHMLXS")
  val lockedAddresses: Set[Address] = Set(
    Address("DAG0qgcEbMk8vQL6VrnbhMreNeEFXk12v1BvERCb"),
    Address("DAG2KQrN97LpA5gRerJAQ5mDuy6kjC2dDtMr58fe"),
    stardustPrimary
  )

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
          addressNotLockedV = validateAddressIsNotLocked(signedTransaction)
        } yield
          signaturesV
            .productR(srcAddressSignatureV)
            .productR(differentSrcAndDstV)
            .productR(addressNotLockedV)

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

      private def validateAddressIsNotLocked(signedTx: Signed[T]): TransactionValidationErrorOr[Signed[T]] =
        if (lockedAddresses.contains(signedTx.value.source))
          AddressLocked(signedTx.value.source).invalidNec[Signed[T]]
        else
          signedTx.validNec[TransactionValidationError]

    }

  @derive(eqv, show)
  sealed trait TransactionValidationError
  case class InvalidSigned(error: SignedValidationError) extends TransactionValidationError
  case object NotSignedBySourceAddressOwner extends TransactionValidationError
  case class SameSourceAndDestinationAddress(address: Address) extends TransactionValidationError
  case class AddressLocked(address: Address) extends TransactionValidationError

  type TransactionValidationErrorOr[A] = ValidatedNec[TransactionValidationError, A]
}
