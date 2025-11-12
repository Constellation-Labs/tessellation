package io.constellationnetwork.node.shared.domain.swap

import cats.data.ValidatedNec
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.ext.cats.syntax.validated._
import io.constellationnetwork.node.shared.config.types.AddressesConfig
import io.constellationnetwork.node.shared.domain.swap.AllowSpendValidator.AllowSpendValidationErrorOr
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.swap.AllowSpend
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.SignedValidator.SignedValidationError
import io.constellationnetwork.security.signature.{Signed, SignedValidator}

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.encoder
import derevo.derive
import eu.timepit.refined.auto._

trait AllowSpendValidator[F[_]] {

  def validate(
    signedAllowSpend: Signed[AllowSpend],
    lastGlobalSnapshotEpochProgress: Option[EpochProgress]
  )(implicit hasher: Hasher[F]): F[AllowSpendValidationErrorOr[Signed[AllowSpend]]]

}

object AllowSpendValidator {

  def make[F[_]: Async](cfg: AddressesConfig, signedValidator: SignedValidator[F]): AllowSpendValidator[F] =
    new AllowSpendValidator[F] {
      def validate(
        signedAllowSpend: Signed[AllowSpend],
        lastGlobalSnapshotEpochProgress: Option[EpochProgress]
      )(implicit hasher: Hasher[F]): F[AllowSpendValidationErrorOr[Signed[AllowSpend]]] =
        for {
          signaturesV <- signedValidator
            .validateSignatures(signedAllowSpend)
            .map(_.errorMap[AllowSpendValidationError](InvalidSigned))
          srcAddressSignatureV <- validateSourceAddressSignature(signedAllowSpend)
          approverV = validateApproverMatches(signedAllowSpend)
          expirationV = validateAllowSpendExpiration(signedAllowSpend, lastGlobalSnapshotEpochProgress)
          addressNotLockedV = validateAddressIsNotLocked(signedAllowSpend)
        } yield
          signaturesV
            .productR(srcAddressSignatureV)
            .productR(approverV)
            .productR(expirationV)
            .productR(addressNotLockedV)

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

      private def validateAddressIsNotLocked(signedTx: Signed[AllowSpend]): AllowSpendValidationErrorOr[Signed[AllowSpend]] =
        if (lockedAddresses.contains(signedTx.value.source))
          AddressLocked(signedTx.value.source).invalidNec[Signed[AllowSpend]]
        else
          signedTx.validNec[AllowSpendValidationError]

      private val lockedAddresses = cfg.locked

    }

  private def validateAllowSpendExpiration(
    signedTx: Signed[AllowSpend],
    lastGlobalSnapshotEpochProgress: Option[EpochProgress]
  ): AllowSpendValidationErrorOr[Signed[AllowSpend]] =
    lastGlobalSnapshotEpochProgress.map { epochProgress =>
      if (signedTx.lastValidEpochProgress > epochProgress) {
        signedTx.validNec
      } else {
        AllowSpendAlreadyExpired.invalidNec
      }
    }.getOrElse(signedTx.validNec)

  @derive(eqv, show)
  sealed trait AllowSpendValidationError
  case class InvalidSigned(error: SignedValidationError) extends AllowSpendValidationError
  case object NotSignedBySourceAddressOwner extends AllowSpendValidationError
  case object AllowSpendAlreadyExpired extends AllowSpendValidationError
  case class InvalidApprover(approvers: List[Address], destination: Address) extends AllowSpendValidationError
  case class AddressLocked(address: Address) extends AllowSpendValidationError

  type AllowSpendValidationErrorOr[A] = ValidatedNec[AllowSpendValidationError, A]
}
