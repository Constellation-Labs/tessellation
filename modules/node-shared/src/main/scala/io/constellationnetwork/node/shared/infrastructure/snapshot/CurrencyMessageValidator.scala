package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.data.{NonEmptySet, Validated, ValidatedNec}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelValidator.validateIfAddressAlreadyUsed
import io.constellationnetwork.node.shared.infrastructure.snapshot.CurrencyMessageValidator.CurrencyMessageOrError
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.currencyMessage.{CurrencyMessage, MessageOrdinal, MessageType}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.signature.SignedValidator.SignedValidationError
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import derevo.cats.{eqv, show}
import derevo.derive

trait CurrencyMessageValidator[F[_]] {
  def validate(
    message: Signed[CurrencyMessage],
    lastMessages: SortedMap[MessageType, Signed[CurrencyMessage]],
    metagraphId: Address,
    existingFeesAddresses: Map[Address, Set[Address]]
  )(
    implicit hasher: Hasher[F]
  ): F[CurrencyMessageOrError]
}

object CurrencyMessageValidator {

  def make[F[_]: Async: SecurityProvider](
    validator: SignedValidator[F],
    allowanceList: Option[Map[Address, NonEmptySet[PeerId]]],
    seedlist: Option[Set[SeedlistEntry]]
  ): CurrencyMessageValidator[F] =
    new CurrencyMessageValidator[F] {
      def validate(
        message: Signed[CurrencyMessage],
        lastMessages: SortedMap[MessageType, Signed[CurrencyMessage]],
        metagraphId: Address,
        existingFeesAddresses: Map[Address, Set[Address]]
      )(implicit hasher: Hasher[F]): F[CurrencyMessageOrError] = {

        val allowancePeers = allowanceList
          .map(
            _.get(metagraphId)
              .map(_.toSortedSet)
              .getOrElse(SortedSet.empty[PeerId])
          )

        val seedlistPeers = seedlist.map(_.map(_.peerId))

        val isMetagraphIdValid = metagraphId === message.metagraphId

        def validateIfSignedBySeedlistPeersExcludingOwner(message: Signed[CurrencyMessage]) =
          message.proofs.toList
            .traverse(sp => sp.id.toAddress.map((sp.id, _)))
            .map(_.collectFirst { case (id, addr) if addr === message.address => id })
            .map(_.flatMap { ownerId =>
              NonEmptySet
                .fromSet(message.proofs.filter(_.id =!= ownerId))
                .map(sigs => Signed(message.value, sigs))
            })
            .map {
              case Some(noOwnerSigMsg) =>
                validator
                  .validateSignaturesWithSeedlist(seedlistPeers, noOwnerSigMsg)
                  .as(message)
              case None => Validated.validNec(message)
            }

        def validateSignatures(message: Signed[CurrencyMessage]) =
          for {
            isSignedByOwner <- validator.isSignedBy(message, message.address)
            hasNoDuplicates = validator.validateUniqueSigners(message)
            isSignedCorrectly <- validator.validateSignatures(message)
            isSignedByMajority = validator.validateSignedBySeedlistMajority(allowancePeers, message)
            isSignedBySeedlistPeers <- validateIfSignedBySeedlistPeersExcludingOwner(message)
          } yield
            isSignedByOwner
              .productR(hasNoDuplicates)
              .productR(isSignedCorrectly)
              .productR(isSignedByMajority)
              .productR(isSignedBySeedlistPeers)
              .leftMap(_.map[CurrencyMessageValidationError](SignatureValidationError(_)))

        lastMessages.get(message.messageType) match {
          case Some(lastMessage) if message.parentOrdinal =!= lastMessage.ordinal =>
            Async[F].pure(NotANextMessage.invalidNec)
          case None if message.parentOrdinal =!= MessageOrdinal.MinValue =>
            Async[F].pure(FirstMessageWithWrongOrdinal.invalidNec)
          case _ if !isMetagraphIdValid =>
            Async[F].pure(WrongMetagraphId.invalidNec)
          case _ =>
            for {
              validSignature <- validateSignatures(message)
              addressNotUsed =
                if (validateIfAddressAlreadyUsed(metagraphId, existingFeesAddresses, message.address.some).isInvalid)
                  AddressAlreadyInUse.invalidNec[CurrencyMessageValidationError].as(message)
                else
                  message.validNec[CurrencyMessageValidationError]
            } yield validSignature.productR(addressNotUsed)
        }
      }
    }

  @derive(eqv, show)
  sealed trait CurrencyMessageValidationError
  case class SignatureValidationError(error: SignedValidationError) extends CurrencyMessageValidationError
  case object WrongMetagraphId extends CurrencyMessageValidationError
  case object NotANextMessage extends CurrencyMessageValidationError
  case object FirstMessageWithWrongOrdinal extends CurrencyMessageValidationError
  case object AddressAlreadyInUse extends CurrencyMessageValidationError

  type CurrencyMessageOrError = ValidatedNec[CurrencyMessageValidationError, Signed[CurrencyMessage]]
}
