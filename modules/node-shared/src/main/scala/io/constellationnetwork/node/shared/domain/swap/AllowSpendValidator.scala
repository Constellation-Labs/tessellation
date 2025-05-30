package io.constellationnetwork.node.shared.domain.swap

import cats.data.ValidatedNec
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.ext.cats.syntax.validated._
import io.constellationnetwork.node.shared.domain.swap.AllowSpendValidator.AllowSpendValidationErrorOr
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.swap.AllowSpend
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.SignedValidator.SignedValidationError
import io.constellationnetwork.security.signature.{Signed, SignedValidator}

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._

trait AllowSpendValidator[F[_]] {

  def validate(signedAllowSpend: Signed[AllowSpend])(implicit hasher: Hasher[F]): F[AllowSpendValidationErrorOr[Signed[AllowSpend]]]

}

object AllowSpendValidator {

  def make[F[_]: Async](signedValidator: SignedValidator[F]): AllowSpendValidator[F] =
    new AllowSpendValidator[F] {
      def validate(
        signedAllowSpend: Signed[AllowSpend]
      )(implicit hasher: Hasher[F]): F[AllowSpendValidationErrorOr[Signed[AllowSpend]]] =
        for {
          signaturesV <- signedValidator
            .validateSignatures(signedAllowSpend)
            .map(_.errorMap[AllowSpendValidationError](InvalidSigned))
          srcAddressSignatureV <- validateSourceAddressSignature(signedAllowSpend)
          approverV = validateApproverMatches(signedAllowSpend)
        } yield
          signaturesV
            .productR(srcAddressSignatureV)
            .productR(approverV)

      private def validateSourceAddressSignature(
        signedTx: Signed[AllowSpend]
      ): F[AllowSpendValidationErrorOr[Signed[AllowSpend]]] =
        signedValidator
          .isSignedExclusivelyBy(signedTx, signedTx.source)
          .map(_.errorMap[AllowSpendValidationError](_ => NotSignedBySourceAddressOwner))

      private def validateApproverMatches(
        signedTx: Signed[AllowSpend]
      ): AllowSpendValidationErrorOr[Signed[AllowSpend]] = {
        val allowSpend = signedTx.value
        val approversValid = allowSpend.approvers.forall { approver =>
          allowSpend.destination === approver
        }

        if (approversValid) {
          signedTx.validNec
        } else {
          InvalidApprover(allowSpend.approvers, allowSpend.destination).invalidNec
        }
      }
    }

  @derive(eqv, show)
  sealed trait AllowSpendValidationError
  case class InvalidSigned(error: SignedValidationError) extends AllowSpendValidationError
  case object NotSignedBySourceAddressOwner extends AllowSpendValidationError
  case class InvalidApprover(approvers: List[Address], destination: Address) extends AllowSpendValidationError

  type AllowSpendValidationErrorOr[A] = ValidatedNec[AllowSpendValidationError, A]
}
