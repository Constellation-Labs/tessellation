package io.constellationnetwork.node.shared.domain.tokenlock

import cats.data.ValidatedNec
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.syntax.validated._
import io.constellationnetwork.node.shared.config.types.AddressesConfig
import io.constellationnetwork.node.shared.domain.tokenlock.TokenLockValidator.TokenLockValidationErrorOr
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.swap.CurrencyId
import io.constellationnetwork.schema.tokenLock.{TokenLock, TokenLockLimitsConfig}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.SignedValidator.SignedValidationError
import io.constellationnetwork.security.signature.{Signed, SignedValidator}

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto.autoUnwrap

trait TokenLockValidator[F[_]] {

  def validate(
    signedTokenLock: Signed[TokenLock],
    lastGlobalSnapshotEpochProgress: Option[EpochProgress]
  )(implicit hasher: Hasher[F]): F[TokenLockValidationErrorOr[Signed[TokenLock]]]

  def validateWithTokenLockLimits(
    signedTokenLock: Signed[TokenLock],
    tokenLockLimitsConfig: TokenLockLimitsConfig,
    maybeCurrentTokenLocks: Option[SortedMap[Address, SortedSet[Signed[TokenLock]]]],
    lastGlobalSnapshotEpochProgress: Option[EpochProgress]
  )(implicit hasher: Hasher[F]): F[TokenLockValidationErrorOr[Signed[TokenLock]]]

}

object TokenLockValidator {
  def make[F[_]: Async](
    cfg: AddressesConfig,
    signedValidator: SignedValidator[F]
  ): TokenLockValidator[F] =
    new TokenLockValidator[F] {
      def validate(
        signedTokenLock: Signed[TokenLock],
        lastGlobalSnapshotEpochProgress: Option[EpochProgress]
      )(implicit hasher: Hasher[F]): F[TokenLockValidationErrorOr[Signed[TokenLock]]] =
        for {
          signaturesV <- signedValidator
            .validateSignatures(signedTokenLock)
            .map(_.errorMap[TokenLockValidationError](InvalidSigned))
          srcAddressSignatureV <- validateSourceAddressSignature(signedTokenLock)
          expirationV = validateTokenLockExpiration(signedTokenLock, lastGlobalSnapshotEpochProgress)
          addressNotLockedV = validateAddressIsNotLocked(signedTokenLock)
        } yield
          signaturesV
            .productR(srcAddressSignatureV)
            .productR(expirationV)
            .productR(addressNotLockedV)

      def validateWithTokenLockLimits(
        signedTokenLock: Signed[TokenLock],
        tokenLockLimitsConfig: TokenLockLimitsConfig,
        maybeCurrentTokenLocks: Option[SortedMap[Address, SortedSet[Signed[TokenLock]]]],
        lastGlobalSnapshotEpochProgress: Option[EpochProgress]
      )(implicit hasher: Hasher[F]): F[TokenLockValidationErrorOr[Signed[TokenLock]]] =
        for {
          signatureValidations <- validate(signedTokenLock, lastGlobalSnapshotEpochProgress)
          currentTokenLocks = maybeCurrentTokenLocks.getOrElse(SortedMap.empty[Address, SortedSet[Signed[TokenLock]]])
          tokenLocksLimitV = validateTokenLocksLimit(
            signedTokenLock,
            tokenLockLimitsConfig,
            currentTokenLocks
          )
          replacementV = validateReplaceTokenLockRef(signedTokenLock, currentTokenLocks)
        } yield
          signatureValidations
            .productR(tokenLocksLimitV)
            .productR(replacementV)

      private def validateSourceAddressSignature(
        signedTx: Signed[TokenLock]
      ): F[TokenLockValidationErrorOr[Signed[TokenLock]]] =
        signedValidator
          .isSignedExclusivelyBy(signedTx, signedTx.source)
          .map(_.errorMap[TokenLockValidationError](_ => NotSignedBySourceAddressOwner))

      private def validateTokenLocksLimit(
        signedTx: Signed[TokenLock],
        tokenLockLimitsConfig: TokenLockLimitsConfig,
        currentTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]]
      ): TokenLockValidationErrorOr[Signed[TokenLock]] = {
        val addressTokenLocks = currentTokenLocks.getOrElse(signedTx.source, SortedSet.empty[Signed[TokenLock]])
        if (signedTx.replaceTokenLockRef.isEmpty && addressTokenLocks.size >= tokenLockLimitsConfig.maxTokenLocksPerAddress.value)
          TooManyTokenLocksForAddress.invalidNec
        else if (signedTx.amount.value < tokenLockLimitsConfig.minTokenLockAmount)
          TokenLockAmountBelowMinimum.invalidNec
        else
          signedTx.validNec
      }

      private def validateTokenLockExpiration(
        signedTx: Signed[TokenLock],
        lastGlobalSnapshotEpochProgress: Option[EpochProgress]
      ): TokenLockValidationErrorOr[Signed[TokenLock]] = {
        val isExpired = for {
          epochProgress <- lastGlobalSnapshotEpochProgress
          unlockEpoch <- signedTx.unlockEpoch
        } yield unlockEpoch <= epochProgress

        if (isExpired.getOrElse(false)) TokenLockExpired.invalidNec else signedTx.validNec
      }

      private def validateAddressIsNotLocked(signedTx: Signed[TokenLock]): TokenLockValidationErrorOr[Signed[TokenLock]] =
        if (lockedAddresses.contains(signedTx.value.source))
          AddressLocked(signedTx.value.source).invalidNec
        else
          signedTx.validNec

      private def validateReplaceTokenLockRef(
        tokenLock: Signed[TokenLock],
        currentTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]]
      ): TokenLockValidationErrorOr[Signed[TokenLock]] =
        tokenLock.replaceTokenLockRef match {
          case None => tokenLock.validNec[TokenLockValidationError]
          case Some(ref) =>
            if (tokenLock.currencyId.nonEmpty)
              (ReplacementIsNotSupported(tokenLock.currencyId): TokenLockValidationError).invalidNec[Signed[TokenLock]]
            else
              tokenLock.validNec[TokenLockValidationError]
        }

      private val lockedAddresses = cfg.locked
    }

  @derive(eqv, show)
  sealed trait TokenLockValidationError
  case class InvalidSigned(error: SignedValidationError) extends TokenLockValidationError
  case object NotSignedBySourceAddressOwner extends TokenLockValidationError
  case object TooManyTokenLocksForAddress extends TokenLockValidationError
  case object TokenLockAmountBelowMinimum extends TokenLockValidationError
  case object TokenLockExpired extends TokenLockValidationError
  case class AddressLocked(address: Address) extends TokenLockValidationError
  case class ReplacementIsNotSupported(currencyId: Option[CurrencyId]) extends TokenLockValidationError
  type TokenLockValidationErrorOr[A] = ValidatedNec[TokenLockValidationError, A]

}
