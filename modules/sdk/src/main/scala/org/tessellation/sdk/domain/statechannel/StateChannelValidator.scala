package org.tessellation.sdk.domain.statechannel

import cats.data.ValidatedNec
import cats.effect.kernel.Async
import cats.syntax.all._

import org.tessellation.ext.cats.syntax.validated._
import org.tessellation.ext.kryo._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.sdk.domain.statechannel.StateChannelValidator.StateChannelValidationErrorOr
import org.tessellation.security.signature.SignedValidator.SignedValidationError
import org.tessellation.security.signature.{Signed, SignedValidator}
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import derevo.cats.{eqv, show}
import derevo.derive

trait StateChannelValidator[F[_]] {

  def validate(stateChannelOutput: StateChannelOutput): F[StateChannelValidationErrorOr[StateChannelOutput]]

}

object StateChannelValidator {

  def make[F[_]: Async: KryoSerializer](
    signedValidator: SignedValidator[F],
    stateChannelSeedlist: Option[Set[Address]],
    maxBinarySizeInBytes: Long = 50 * 1024
  ): StateChannelValidator[F] = new StateChannelValidator[F] {

    override def validate(stateChannelOutput: StateChannelOutput): F[StateChannelValidationErrorOr[StateChannelOutput]] = for {
      signaturesV <- signedValidator
        .validateSignatures(stateChannelOutput.snapshotBinary)
        .map(_.errorMap[StateChannelValidationError](InvalidSigned))
      addressSignatureV <- validateSourceAddressSignature(stateChannelOutput.address, stateChannelOutput.snapshotBinary)
      snapshotSizeV <- validateSnapshotSize(stateChannelOutput.snapshotBinary)
      stateChannelAddressV = validateStateChannelAddress(stateChannelOutput.address)
    } yield signaturesV.productR(addressSignatureV).product(snapshotSizeV).product(stateChannelAddressV).map(_ => stateChannelOutput)

    private def validateSourceAddressSignature(
      address: Address,
      signedSC: Signed[StateChannelSnapshotBinary]
    ): F[StateChannelValidationErrorOr[Signed[StateChannelSnapshotBinary]]] =
      signedValidator
        .isSignedExclusivelyBy(signedSC, address)
        .map(_.errorMap[StateChannelValidationError](_ => NotSignedExclusivelyByStateChannelOwner))

    private def validateSnapshotSize(
      signedSC: Signed[StateChannelSnapshotBinary]
    ): F[StateChannelValidationErrorOr[Signed[StateChannelSnapshotBinary]]] =
      signedSC.toBinaryF.map { binary =>
        val actualSize = binary.size
        val isWithinLimit = actualSize <= maxBinarySizeInBytes

        if (isWithinLimit)
          signedSC.validNec
        else
          BinaryExceedsMaxAllowedSize(maxBinarySizeInBytes, actualSize).invalidNec
      }

    private def validateStateChannelAddress(address: Address): StateChannelValidationErrorOr[Address] =
      if (stateChannelSeedlist.forall(_.contains(address)))
        address.validNec
      else
        StateChannelAddressNotAllowed(address).invalidNec
  }

  @derive(eqv, show)
  sealed trait StateChannelValidationError
  case class InvalidSigned(error: SignedValidationError) extends StateChannelValidationError
  case object NotSignedExclusivelyByStateChannelOwner extends StateChannelValidationError
  case class BinaryExceedsMaxAllowedSize(maxSize: Long, was: Int) extends StateChannelValidationError

  case class StateChannelAddressNotAllowed(address: Address) extends StateChannelValidationError

  type StateChannelValidationErrorOr[A] = ValidatedNec[StateChannelValidationError, A]

}
