package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.data.{NonEmptySet, Validated, ValidatedNec}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.env.AppEnvironment.Dev
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelValidator.validateIfAddressAlreadyUsed
import io.constellationnetwork.node.shared.infrastructure.snapshot.CurrencyMessageValidator.CurrencyMessageOrError
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.currencyMessage.MessageType.Staking
import io.constellationnetwork.schema.currencyMessage.{CurrencyMessage, MessageOrdinal, MessageType}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.signature.SignedValidator.SignedValidationError
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto.autoUnwrap

trait CurrencyMessageValidator[F[_]] {
  def validateInitialOwner(
    message: Signed[CurrencyMessage],
    metagraphId: Address,
    existingFeesAddresses: Map[Address, Set[Address]],
    shouldPerformMetagraphSpecificValidations: Boolean
  )(implicit hasher: Hasher[F]): F[CurrencyMessageOrError]

  def validate(
    message: Signed[CurrencyMessage],
    lastMessages: SortedMap[MessageType, Signed[CurrencyMessage]],
    metagraphId: Address,
    existingFeesAddresses: Map[Address, Set[Address]],
    shouldPerformMetagraphSpecificValidations: Boolean
  )(implicit hasher: Hasher[F]): F[CurrencyMessageOrError]
}

object CurrencyMessageValidator {

  def make[F[_]](
    environment: AppEnvironment,
    validator: SignedValidator[F],
    allowanceList: Option[Map[Address, NonEmptySet[PeerId]]],
    seedlist: Option[Set[SeedlistEntry]]
  )(implicit F: Async[F], sp: SecurityProvider[F]): CurrencyMessageValidator[F] =
    new CurrencyMessageValidatorImpl[F](environment, validator, allowanceList, seedlist)

  private class CurrencyMessageValidatorImpl[F[_]](
    environment: AppEnvironment,
    validator: SignedValidator[F],
    allowanceList: Option[Map[Address, NonEmptySet[PeerId]]],
    seedlist: Option[Set[SeedlistEntry]]
  )(implicit F: Async[F], sp: SecurityProvider[F])
      extends CurrencyMessageValidator[F] {

    private val seedlistPeers: Option[Set[PeerId]] = seedlist.map(_.map(_.peerId))

    def validateInitialOwner(
      message: Signed[CurrencyMessage],
      metagraphId: Address,
      existingFeesAddresses: Map[Address, Set[Address]],
      shouldPerformMetagraphSpecificValidations: Boolean
    )(implicit hasher: Hasher[F]): F[CurrencyMessageOrError] =
      validateCore(
        message = message,
        metagraphId = metagraphId,
        existingFeesAddresses = existingFeesAddresses,
        lastMessage = None,
        isInitialOwner = true,
        shouldPerformMetagraphSpecificValidations = shouldPerformMetagraphSpecificValidations
      )

    def validate(
      message: Signed[CurrencyMessage],
      lastMessages: SortedMap[MessageType, Signed[CurrencyMessage]],
      metagraphId: Address,
      existingFeesAddresses: Map[Address, Set[Address]],
      shouldPerformMetagraphSpecificValidations: Boolean
    )(implicit hasher: Hasher[F]): F[CurrencyMessageOrError] = {
      val lastMessage = lastMessages.get(message.messageType)
      validateCore(
        message = message,
        metagraphId = metagraphId,
        existingFeesAddresses = existingFeesAddresses,
        lastMessage = lastMessage,
        isInitialOwner = false,
        shouldPerformMetagraphSpecificValidations = shouldPerformMetagraphSpecificValidations
      )
    }

    private def validateCore(
      message: Signed[CurrencyMessage],
      metagraphId: Address,
      existingFeesAddresses: Map[Address, Set[Address]],
      lastMessage: Option[Signed[CurrencyMessage]],
      isInitialOwner: Boolean,
      shouldPerformMetagraphSpecificValidations: Boolean
    )(implicit hasher: Hasher[F]): F[CurrencyMessageOrError] =
      validateBasicRequirements(message, metagraphId, lastMessage) match {
        case Some(error) => F.pure(error.invalidNec)
        case None =>
          for {
            signatureValidation <- validateSignatures(message, isInitialOwner, shouldPerformMetagraphSpecificValidations)
            addressValidation = validateAddress(message, metagraphId, existingFeesAddresses)
          } yield signatureValidation.productR(addressValidation)
      }

    private def validateBasicRequirements(
      message: Signed[CurrencyMessage],
      metagraphId: Address,
      lastMessage: Option[Signed[CurrencyMessage]]
    ): Option[CurrencyMessageValidationError] =
      if (metagraphId =!= message.metagraphId) {
        Some(WrongMetagraphId)
      } else {
        lastMessage match {
          case Some(last) if message.parentOrdinal =!= last.ordinal =>
            Some(NotANextMessage)
          case None if message.parentOrdinal =!= MessageOrdinal.MinValue =>
            Some(FirstMessageWithWrongOrdinal)
          case _ => None
        }
      }

    private def validateAddress(
      message: Signed[CurrencyMessage],
      metagraphId: Address,
      existingFeesAddresses: Map[Address, Set[Address]]
    ): ValidatedNec[CurrencyMessageValidationError, Signed[CurrencyMessage]] =
      if (validateIfAddressAlreadyUsed(metagraphId, existingFeesAddresses, message.address.some).isInvalid) {
        AddressAlreadyInUse.invalidNec
      } else {
        message.validNec
      }

    private def validateSignatures(
      message: Signed[CurrencyMessage],
      isInitialOwner: Boolean,
      shouldPerformMetagraphSpecificValidations: Boolean
    )(implicit hasher: Hasher[F]): F[ValidatedNec[CurrencyMessageValidationError, Signed[CurrencyMessage]]] = {
      val uniqueSigners = validator.validateUniqueSigners(message)
      val majoritySignature = if (isInitialOwner) {
        Validated.validNec(message)
      } else {
        validator.validateSignedBySeedlistMajority(getAllowancePeers(message.metagraphId), message)
      }

      for {
        ownerSignature <- validator.isSignedBy(message, message.address)
        correctSignatures <- validator.validateSignatures(message)
        seedlistSignature = validateSeedlistSignature(message, shouldPerformMetagraphSpecificValidations)
      } yield
        combineSignatureValidations(
          ownerSignature,
          uniqueSigners,
          correctSignatures,
          majoritySignature,
          seedlistSignature
        )
    }

    private def getAllowancePeers(metagraphId: Address): Option[SortedSet[PeerId]] =
      allowanceList
        .flatMap(_.get(metagraphId))
        .map(_.toSortedSet)

    /** Validates the signature of a currency message against the seedlist.
      *
      * When metagraph-specific validations are disabled, this method skips seedlist validation to allow for differences between metagraph
      * and hypergraph seedlists. This is useful when the metagraph operates with a different set of trusted peers than the hypergraph.
      *
      * @param message
      *   the signed currency message to validate
      * @param shouldPerformMetagraphSpecificValidations
      *   if false, skips seedlist validation to allow seedlist differences between metagraph and hypergraph; if true, validates signatures
      *   against the seedlist
      * @return
      *   a ValidatedNec containing either the original message if validation passes/is skipped, or accumulated SignedValidationErrors if
      *   validation fails
      */
    private def validateSeedlistSignature(
      message: Signed[CurrencyMessage],
      shouldPerformMetagraphSpecificValidations: Boolean
    ): ValidatedNec[SignedValidationError, Signed[CurrencyMessage]] =
      if (!shouldPerformMetagraphSpecificValidations) {
        Validated.validNec(message)
      } else {
        validator
          .validateSignaturesWithSeedlist(seedlistPeers, message)
          .map(_.as(message))
      }

    private def combineSignatureValidations(
      ownerSignature: ValidatedNec[SignedValidationError, Signed[CurrencyMessage]],
      uniqueSigners: ValidatedNec[SignedValidationError, Signed[CurrencyMessage]],
      correctSignatures: ValidatedNec[SignedValidationError, Signed[CurrencyMessage]],
      majoritySignature: ValidatedNec[SignedValidationError, Signed[CurrencyMessage]],
      seedlistSignature: ValidatedNec[SignedValidationError, Signed[CurrencyMessage]]
    ): ValidatedNec[CurrencyMessageValidationError, Signed[CurrencyMessage]] =
      ownerSignature
        .productR(uniqueSigners)
        .productR(correctSignatures)
        .productR(majoritySignature)
        .productR(seedlistSignature)
        .leftMap(_.map[CurrencyMessageValidationError](SignatureValidationError(_)))
  }

  @derive(eqv, show)
  sealed trait CurrencyMessageValidationError

  case class SignatureValidationError(error: SignedValidationError) extends CurrencyMessageValidationError
  case object WrongMetagraphId extends CurrencyMessageValidationError
  case object NotANextMessage extends CurrencyMessageValidationError
  case object FirstMessageWithWrongOrdinal extends CurrencyMessageValidationError
  case object AddressAlreadyInUse extends CurrencyMessageValidationError
  case object AddressBalanceNotEnough extends CurrencyMessageValidationError

  type CurrencyMessageOrError = ValidatedNec[CurrencyMessageValidationError, Signed[CurrencyMessage]]
}
