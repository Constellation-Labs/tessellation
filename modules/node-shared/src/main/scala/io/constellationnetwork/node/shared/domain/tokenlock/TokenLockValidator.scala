package io.constellationnetwork.node.shared.domain.tokenlock

import cats.data.ValidatedNec
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.syntax.validated._
import io.constellationnetwork.node.shared.config.types.DelegatedStakingConfig
import io.constellationnetwork.node.shared.domain.tokenlock.TokenLockValidator.TokenLockValidationErrorOr
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.tokenLock.{TokenLock, TokenLockLimitsConfig}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.SignedValidator.SignedValidationError
import io.constellationnetwork.security.signature.{Signed, SignedValidator}

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto.autoUnwrap

trait TokenLockValidator[F[_]] {

  def validate(
    signedTokenLock: Signed[TokenLock]
  )(implicit hasher: Hasher[F]): F[TokenLockValidationErrorOr[Signed[TokenLock]]]

  def validateWithTokenLockLimits(
    signedTokenLock: Signed[TokenLock],
    tokenLockLimitsConfig: TokenLockLimitsConfig,
    maybeCurrentTokenLocks: Option[SortedMap[Address, SortedSet[Signed[TokenLock]]]]
  )(implicit hasher: Hasher[F]): F[TokenLockValidationErrorOr[Signed[TokenLock]]]

}

object TokenLockValidator {
  def make[F[_]: Async](
    signedValidator: SignedValidator[F]
  ): TokenLockValidator[F] =
    new TokenLockValidator[F] {
      def validate(
        signedTokenLock: Signed[TokenLock]
      )(implicit hasher: Hasher[F]): F[TokenLockValidationErrorOr[Signed[TokenLock]]] =
        for {
          signaturesV <- signedValidator
            .validateSignatures(signedTokenLock)
            .map(_.errorMap[TokenLockValidationError](InvalidSigned))
          srcAddressSignatureV <- validateSourceAddressSignature(signedTokenLock)
        } yield
          signaturesV
            .productR(srcAddressSignatureV)

      def validateWithTokenLockLimits(
        signedTokenLock: Signed[TokenLock],
        tokenLockLimitsConfig: TokenLockLimitsConfig,
        maybeCurrentTokenLocks: Option[SortedMap[Address, SortedSet[Signed[TokenLock]]]]
      )(implicit hasher: Hasher[F]): F[TokenLockValidationErrorOr[Signed[TokenLock]]] =
        for {
          signatureValidations <- validate(signedTokenLock)
          tokenLocksLimitV = validateTokenLocksLimit(
            signedTokenLock,
            tokenLockLimitsConfig,
            maybeCurrentTokenLocks.getOrElse(SortedMap.empty)
          )
        } yield
          signatureValidations
            .productR(tokenLocksLimitV)

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
        if (addressTokenLocks.size === tokenLockLimitsConfig.maxTokenLocksPerAddress.value)
          TooManyTokenLocksForAddress.invalidNec
        else if (signedTx.amount.value < tokenLockLimitsConfig.minTokenLockAmount)
          TokenLockAmountBelowMinimum.invalidNec
        else
          signedTx.validNec
      }
    }

  @derive(eqv, show)
  sealed trait TokenLockValidationError
  case class InvalidSigned(error: SignedValidationError) extends TokenLockValidationError
  case object NotSignedBySourceAddressOwner extends TokenLockValidationError
  case object TooManyTokenLocksForAddress extends TokenLockValidationError
  case object TokenLockAmountBelowMinimum extends TokenLockValidationError
  type TokenLockValidationErrorOr[A] = ValidatedNec[TokenLockValidationError, A]

}
