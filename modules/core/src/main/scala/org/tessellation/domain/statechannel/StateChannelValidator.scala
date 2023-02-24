package org.tessellation.domain.statechannel

import cats.data.ValidatedNec
import cats.effect.kernel.Async
import cats.syntax.all._

import org.tessellation.domain.statechannel.StateChannelValidator.StateChannelValidationErrorOr
import org.tessellation.ext.cats.syntax.validated._
import org.tessellation.schema.address.Address
import org.tessellation.security.signature.SignedValidator.SignedValidationError
import org.tessellation.security.signature.{Signed, SignedValidator}
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import derevo.cats.{eqv, show}
import derevo.derive

trait StateChannelValidator[F[_]] {

  def validate(stateChannelOutput: StateChannelOutput): F[StateChannelValidationErrorOr[StateChannelOutput]]

}

object StateChannelValidator {

  def make[F[_]: Async](
    signedValidator: SignedValidator[F]
  ): StateChannelValidator[F] = new StateChannelValidator[F] {

    override def validate(stateChannelOutput: StateChannelOutput): F[StateChannelValidationErrorOr[StateChannelOutput]] = for {
      signaturesV <- signedValidator
        .validateSignatures(stateChannelOutput.snapshotBinary)
        .map(_.errorMap[StateChannelValidationError](InvalidSigned))
      addressSignatureV <- validateSourceAddressSignature(stateChannelOutput.address, stateChannelOutput.snapshotBinary)
    } yield signaturesV.productR(addressSignatureV).map(_ => stateChannelOutput)

    private def validateSourceAddressSignature(
      address: Address,
      signedSC: Signed[StateChannelSnapshotBinary]
    ): F[StateChannelValidationErrorOr[Signed[StateChannelSnapshotBinary]]] =
      signedValidator
        .isSignedExclusivelyBy(signedSC, address)
        .map(_.errorMap[StateChannelValidationError](_ => NotSignedExclusivelyByStateChannelOwner))
  }

  @derive(eqv, show)
  sealed trait StateChannelValidationError
  case class InvalidSigned(error: SignedValidationError) extends StateChannelValidationError
  case object NotSignedExclusivelyByStateChannelOwner extends StateChannelValidationError

  type StateChannelValidationErrorOr[A] = ValidatedNec[StateChannelValidationError, A]

}
