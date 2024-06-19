package org.tessellation.node.shared.domain.transaction

import cats.data.ValidatedNec
import cats.effect.Async
import cats.syntax.all._

import org.tessellation.ext.cats.syntax.validated._
import org.tessellation.node.shared.domain.transaction.AllowSpendValidator.AllowSpendTransactionValidationErrorOr
import org.tessellation.schema.address.Address
import org.tessellation.schema.swap._
import org.tessellation.security.Hasher
import org.tessellation.security.signature.SignedValidator.SignedValidationError
import org.tessellation.security.signature.{Signed, SignedValidator}

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._

trait AllowSpendValidator[F[_]] {

  def validate(signedTransaction: Signed[AllowSpend]): F[AllowSpendTransactionValidationErrorOr[Signed[AllowSpend]]]

}

object AllowSpendValidator {
  val stardustPrimary: Address = Address("DAGSTARDUSTCOLLECTIVEHZOIPHXZUBFGNXWJETZVSPAPAHMLXS")
  val lockedAddresses: Set[Address] = Set(
    Address("DAG0qgcEbMk8vQL6VrnbhMreNeEFXk12v1BvERCb"),
    Address("DAG2KQrN97LpA5gRerJAQ5mDuy6kjC2dDtMr58fe"),
    stardustPrimary
  )

  def make[F[_]: Async](
    signedValidator: SignedValidator[F],
    txHasher: Hasher[F]
  ): AllowSpendValidator[F] =
    new AllowSpendValidator[F] {
      def validate(
        signedTransaction: Signed[AllowSpend]
      ): F[AllowSpendTransactionValidationErrorOr[Signed[AllowSpend]]] = {
        implicit val hasher = txHasher

        for {
          signaturesV <- signedValidator
            .validateSignatures(signedTransaction)
            .map(_.errorMap[AllowSpendTransactionValidationError](InvalidSigned))
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
        signedTx: Signed[AllowSpend]
      ): F[AllowSpendTransactionValidationErrorOr[Signed[AllowSpend]]] =
        signedValidator
          .isSignedExclusivelyBy(signedTx, signedTx.source)
          .map(_.errorMap[AllowSpendTransactionValidationError](_ => NotSignedBySourceAddressOwner))

      private def validateDifferentSourceAndDestinationAddress(
        signedTx: Signed[AllowSpend]
      ): AllowSpendTransactionValidationErrorOr[Signed[AllowSpend]] =
        if (signedTx.source =!= signedTx.destination)
          signedTx.validNec[AllowSpendTransactionValidationError]
        else
          SameSourceAndDestinationAddress(signedTx.source).invalidNec[Signed[AllowSpend]]

      private def validateAddressIsNotLocked(signedTx: Signed[AllowSpend]): AllowSpendTransactionValidationErrorOr[Signed[AllowSpend]] =
        if (lockedAddresses.contains(signedTx.value.source))
          AddressLocked(signedTx.value.source).invalidNec[Signed[AllowSpend]]
        else
          signedTx.validNec[AllowSpendTransactionValidationError]

    }

  @derive(eqv, show)
  sealed trait AllowSpendTransactionValidationError
  case class InvalidSigned(error: SignedValidationError) extends AllowSpendTransactionValidationError
  case object NotSignedBySourceAddressOwner extends AllowSpendTransactionValidationError
  case class SameSourceAndDestinationAddress(address: Address) extends AllowSpendTransactionValidationError
  case class AddressLocked(address: Address) extends AllowSpendTransactionValidationError

  type AllowSpendTransactionValidationErrorOr[A] = ValidatedNec[AllowSpendTransactionValidationError, A]
}
