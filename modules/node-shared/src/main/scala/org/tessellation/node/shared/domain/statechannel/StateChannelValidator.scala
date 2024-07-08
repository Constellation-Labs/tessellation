package org.tessellation.node.shared.domain.statechannel

import cats.data.{NonEmptySet, ValidatedNec}
import cats.effect.kernel.Async
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.validated._

import org.tessellation.currency.schema.currency.SnapshotFee
import org.tessellation.ext.cats.syntax.validated._
import org.tessellation.json.{JsonSerializer, SizeCalculator}
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.node.shared.domain.statechannel.StateChannelValidator.StateChannelValidationErrorOr
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import org.tessellation.security.Hasher
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.SignedValidator.SignedValidationError
import org.tessellation.security.signature.{Signed, SignedValidator}
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosLong

trait StateChannelValidator[F[_]] {

  def validate(
    stateChannelOutput: StateChannelOutput,
    snapshotOrdinal: SnapshotOrdinal,
    snapshotFeesInfo: SnapshotFeesInfo
  )(implicit hasher: Hasher[F]): F[StateChannelValidationErrorOr[StateChannelOutput]]

  def validateHistorical(
    stateChannelOutput: StateChannelOutput,
    snapshotOrdinal: SnapshotOrdinal,
    snapshotFeesInfo: SnapshotFeesInfo
  )(
    implicit hasher: Hasher[F]
  ): F[StateChannelValidationErrorOr[StateChannelOutput]]
}

object StateChannelValidator {
  def getFeeAddresses(info: GlobalSnapshotInfo): Map[Address, Set[Address]] =
    info.lastCurrencySnapshots.collect {
      case (address, Right((_, info))) => address -> info.lastMessages.toList.flatMap(_.values.map(_.address).toList).toSet
    }

  def validateIfAddressAlreadyUsed(
    metagraphId: Address,
    existingFeesAddresses: Map[Address, Set[Address]],
    maybeAddress: Option[Address]
  ): StateChannelValidationErrorOr[Unit] = maybeAddress match {
    case None => ().validNec
    case Some(address) =>
      val existsInMetagraphAddresses = existingFeesAddresses.get(metagraphId).exists(_.contains(address))
      if (existsInMetagraphAddresses) {
        ().validNec
      } else {
        val existsInAllAddresses = existingFeesAddresses.exists { case (_, addresses) => addresses.contains(address) }
        if (existsInAllAddresses) {
          AddressAlreadyInUse.invalidNec
        } else {
          ().validNec
        }
      }
  }

  def make[F[_]: Async: JsonSerializer](
    signedValidator: SignedValidator[F],
    l0Seedlist: Option[Set[SeedlistEntry]],
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]],
    maxBinarySizeInBytes: PosLong,
    feeCalculator: FeeCalculator[F]
  ): StateChannelValidator[F] = new StateChannelValidator[F] {

    def validate(
      stateChannelOutput: StateChannelOutput,
      globalOrdinal: SnapshotOrdinal,
      snapshotFeesInfo: SnapshotFeesInfo
    )(implicit hasher: Hasher[F]): F[StateChannelValidationErrorOr[StateChannelOutput]] =
      for {
        historical <- validateHistorical(stateChannelOutput, globalOrdinal, snapshotFeesInfo)
        allowedSignatures = validateAllowedSignatures(stateChannelOutput)
      } yield
        historical
          .product(allowedSignatures)
          .as(stateChannelOutput)

    def validateHistorical(
      stateChannelOutput: StateChannelOutput,
      globalOrdinal: SnapshotOrdinal,
      snapshotFeesInfo: SnapshotFeesInfo
    )(implicit hasher: Hasher[F]): F[StateChannelValidationErrorOr[StateChannelOutput]] =
      for {
        signaturesV <- signedValidator
          .validateSignatures(stateChannelOutput.snapshotBinary)
          .map(_.errorMap[StateChannelValidationError](InvalidSigned))
        snapshotSizeV <- validateSnapshotSize(stateChannelOutput.snapshotBinary)
        snapshotFeeV <- validateSnapshotFee(stateChannelOutput.snapshotBinary, globalOrdinal, snapshotFeesInfo.stakingBalance)
        genesisAddressV = validateStateChannelGenesisAddress(stateChannelOutput.address, stateChannelOutput.snapshotBinary)
        ownerAddressValidationV = validateIfAddressAlreadyUsed(
          stateChannelOutput.address,
          snapshotFeesInfo.allFeesAddresses,
          snapshotFeesInfo.ownerAddress
        )
        stakingAddressValidationV = validateIfAddressAlreadyUsed(
          stateChannelOutput.address,
          snapshotFeesInfo.allFeesAddresses,
          snapshotFeesInfo.stakingAddress
        )
      } yield
        signaturesV
          .product(snapshotSizeV)
          .product(snapshotFeeV)
          .product(genesisAddressV)
          .product(ownerAddressValidationV)
          .product(stakingAddressValidationV)
          .as(stateChannelOutput)

    private def validateSnapshotSize(
      signedSC: Signed[StateChannelSnapshotBinary]
    ): F[StateChannelValidationErrorOr[Signed[StateChannelSnapshotBinary]]] =
      SizeCalculator.bytes(signedSC).map { actualSize =>
        val isWithinLimit = actualSize <= maxBinarySizeInBytes

        if (isWithinLimit)
          signedSC.validNec
        else
          BinaryExceedsMaxAllowedSize(maxBinarySizeInBytes, actualSize).invalidNec
      }

    private def validateSnapshotFee(
      signedSC: Signed[StateChannelSnapshotBinary],
      globalOrdinal: SnapshotOrdinal,
      staked: Balance
    ): F[StateChannelValidationErrorOr[Signed[StateChannelSnapshotBinary]]] =
      SizeCalculator.kilobytes(signedSC).flatMap { sizeKb =>
        feeCalculator.calculateRecommendedFee(globalOrdinal.some)(staked, sizeKb).map { minFee =>
          val isSufficientFee = minFee.value <= signedSC.fee.value

          if (isSufficientFee)
            signedSC.validNec
          else
            BinaryFeeNotSufficient(signedSC.fee, minFee, sizeKb, globalOrdinal).invalidNec
        }
      }

    private def validateAllowedSignatures(stateChannelOutput: StateChannelOutput) =
      validateSignaturesWithSeedlist(stateChannelOutput.snapshotBinary)
        .andThen(_ => validateStateChannelAddress(stateChannelOutput.address))
        .andThen(_ => validateStateChannelAllowanceList(stateChannelOutput.address, stateChannelOutput.snapshotBinary))

    private def validateSignaturesWithSeedlist(
      signed: Signed[StateChannelSnapshotBinary]
    ): StateChannelValidationErrorOr[Signed[StateChannelSnapshotBinary]] =
      signedValidator
        .validateSignaturesWithSeedlist(l0Seedlist.map(_.map(_.peerId)), signed)
        .errorMap(SignersNotInSeedlist)

    private def validateStateChannelAddress(address: Address): StateChannelValidationErrorOr[Address] =
      if (stateChannelAllowanceLists.forall(_.contains(address)))
        address.validNec
      else
        StateChannelAddressNotAllowed(address).invalidNec

    private def validateStateChannelAllowanceList(
      address: Address,
      signedSC: Signed[StateChannelSnapshotBinary]
    ): StateChannelValidationErrorOr[Signed[StateChannelSnapshotBinary]] =
      stateChannelAllowanceLists match {
        case None => signedSC.validNec
        case Some(signersPerAddress) =>
          signersPerAddress
            .get(address)
            .flatMap { peers =>
              signedSC.proofs
                .map(_.id.toPeerId)
                .toSortedSet
                .find(peers.contains)
            }
            .as(signedSC)
            .toValidNec(NoSignerFromStateChannelAllowanceList)
      }

    private def validateStateChannelGenesisAddress(
      address: Address,
      signedSC: Signed[StateChannelSnapshotBinary]
    ): StateChannelValidationErrorOr[Signed[StateChannelSnapshotBinary]] =
      if (signedSC.value.lastSnapshotHash === Hash.empty && signedSC.value.toAddress =!= address)
        StateChannellGenesisAddressNotGeneratedFromData(address).invalidNec
      else
        signedSC.validNec
  }

  @derive(eqv, show, decoder, encoder)
  sealed trait StateChannelValidationError
  case class InvalidSigned(error: SignedValidationError) extends StateChannelValidationError
  case object NotSignedExclusivelyByStateChannelOwner extends StateChannelValidationError
  case class BinaryExceedsMaxAllowedSize(maxSize: Long, was: Int) extends StateChannelValidationError
  case class BinaryFeeNotSufficient(actual: SnapshotFee, minimum: SnapshotFee, sizeKb: Int, ordinal: SnapshotOrdinal)
      extends StateChannelValidationError
  case class SignersNotInSeedlist(error: SignedValidationError) extends StateChannelValidationError
  case class StateChannelAddressNotAllowed(address: Address) extends StateChannelValidationError
  case object NoSignerFromStateChannelAllowanceList extends StateChannelValidationError
  case class StateChannellGenesisAddressNotGeneratedFromData(address: Address) extends StateChannelValidationError
  case object AddressAlreadyInUse extends StateChannelValidationError

  type StateChannelValidationErrorOr[A] = ValidatedNec[StateChannelValidationError, A]

}
