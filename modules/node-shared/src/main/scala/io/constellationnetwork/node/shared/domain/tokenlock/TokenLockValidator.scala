package io.constellationnetwork.node.shared.domain.tokenlock

import cats.data.ValidatedNec
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.ext.cats.syntax.validated._
import io.constellationnetwork.node.shared.domain.tokenlock.TokenLockValidator.TokenLockValidationErrorOr
import io.constellationnetwork.schema.tokenLock.TokenLock
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.SignedValidator.SignedValidationError
import io.constellationnetwork.security.signature.{Signed, SignedValidator}

import derevo.cats.{eqv, show}
import derevo.derive

trait TokenLockValidator[F[_]] {

  def validate(signedTokenLock: Signed[TokenLock])(implicit hasher: Hasher[F]): F[TokenLockValidationErrorOr[Signed[TokenLock]]]

}

object TokenLockValidator {

  def make[F[_]: Async](signedValidator: SignedValidator[F]): TokenLockValidator[F] =
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

      private def validateSourceAddressSignature(
        signedTx: Signed[TokenLock]
      ): F[TokenLockValidationErrorOr[Signed[TokenLock]]] =
        signedValidator
          .isSignedExclusivelyBy(signedTx, signedTx.source)
          .map(_.errorMap[TokenLockValidationError](_ => NotSignedBySourceAddressOwner))

    }

  @derive(eqv, show)
  sealed trait TokenLockValidationError
  case class InvalidSigned(error: SignedValidationError) extends TokenLockValidationError
  case object NotSignedBySourceAddressOwner extends TokenLockValidationError

  type TokenLockValidationErrorOr[A] = ValidatedNec[TokenLockValidationError, A]
}
