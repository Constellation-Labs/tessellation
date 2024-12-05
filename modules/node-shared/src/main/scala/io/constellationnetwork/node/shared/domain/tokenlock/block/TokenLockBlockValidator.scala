package io.constellationnetwork.node.shared.domain.tokenlock.block

import cats.Functor
import cats.data.ValidatedNec
import cats.effect.Async
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.partialOrder._
import cats.syntax.validated._

import io.constellationnetwork.ext.cats.syntax.validated._
import io.constellationnetwork.node.shared.domain.tokenlock.TokenLockChainValidator.{TokenLockChainBroken, TokenLockNel}
import io.constellationnetwork.node.shared.domain.tokenlock.TokenLockValidator.TokenLockValidationError
import io.constellationnetwork.node.shared.domain.tokenlock.{TokenLockChainValidator, TokenLockValidator}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.tokenLock.{TokenLockBlock, TokenLockReference}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.SignedValidator.SignedValidationError
import io.constellationnetwork.security.signature.{Signed, SignedValidator}

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt

trait TokenLockBlockValidator[F[_]] {

  type TokenLockBlockValidationErrorOr[A] = ValidatedNec[TokenLockBlockValidationError, A]

  def validate(
    signedBlock: Signed[TokenLockBlock],
    snapshotOrdinal: SnapshotOrdinal,
    params: TokenLockBlockValidationParams = TokenLockBlockValidationParams.default
  )(implicit hasher: Hasher[F]): F[TokenLockBlockValidationErrorOr[(Signed[TokenLockBlock], Map[Address, TokenLockNel])]]

  def validateGetBlock(
    signedBlock: Signed[TokenLockBlock],
    params: TokenLockBlockValidationParams = TokenLockBlockValidationParams.default,
    epochProgress: EpochProgress,
    snapshotOrdinal: SnapshotOrdinal
  )(implicit ev: Functor[F], hasher: Hasher[F]): F[TokenLockBlockValidationErrorOr[Signed[TokenLockBlock]]] =
    validate(signedBlock, snapshotOrdinal, params).map(_.map(_._1))

  def validateGetTxChains(
    signedBlock: Signed[TokenLockBlock],
    snapshotOrdinal: SnapshotOrdinal,
    epochProgress: EpochProgress,
    params: TokenLockBlockValidationParams = TokenLockBlockValidationParams.default
  )(implicit ev: Functor[F], hasher: Hasher[F]): F[TokenLockBlockValidationErrorOr[Map[Address, TokenLockNel]]] =
    validate(signedBlock, snapshotOrdinal, params).map(_.map(_._2))
}

object TokenLockBlockValidator {

  def make[F[_]: Async](
    signedValidator: SignedValidator[F],
    TokenLockChainValidator: TokenLockChainValidator[F],
    TokenLockValidator: TokenLockValidator[F]
  ): TokenLockBlockValidator[F] =
    new TokenLockBlockValidator[F] {

      def validate(
        signedBlock: Signed[TokenLockBlock],
        snapshotOrdinal: SnapshotOrdinal,
        params: TokenLockBlockValidationParams
      )(implicit hasher: Hasher[F]): F[TokenLockBlockValidationErrorOr[(Signed[TokenLockBlock], Map[Address, TokenLockNel])]] =
        for {
          signedV <- validateSigned(signedBlock, params)(hasher)
          lockedV = validateNotLockedAtOrdinal(signedBlock, snapshotOrdinal)
          transactionsV <- validateTokenLocks(signedBlock)
          transactionChainV <- validateTokenLockChain(signedBlock)
        } yield
          signedV
            .productR(lockedV)
            .productR(transactionsV)
            .product(transactionChainV)

      private def validateSigned(
        signedBlock: Signed[TokenLockBlock],
        params: TokenLockBlockValidationParams
      )(implicit hasher: Hasher[F]): F[TokenLockBlockValidationErrorOr[Signed[TokenLockBlock]]] =
        signedValidator
          .validateSignatures(signedBlock)
          .map { signaturesV =>
            signaturesV
              .productR(signedValidator.validateUniqueSigners(signedBlock))
              .productR(signedValidator.validateMinSignatureCount(signedBlock, params.minSignatureCount))
          }
          .map(_.errorMap[TokenLockBlockValidationError](InvalidSigned))

      private def validateTokenLocks(
        signedBlock: Signed[TokenLockBlock]
      )(implicit hasher: Hasher[F]): F[TokenLockBlockValidationErrorOr[Signed[TokenLockBlock]]] =
        signedBlock.value.tokenLocks.toNonEmptyList.traverse { signedTransaction =>
          for {
            txRef <- TokenLockReference.of(signedTransaction)
            txV <- TokenLockValidator.validate(signedTransaction)
          } yield txV.errorMap(InvalidTokenLock(txRef, _))
        }.map { vs =>
          vs.foldLeft(signedBlock.validNec[TokenLockBlockValidationError]) { (acc, v) =>
            acc.productL(v)
          }
        }

      private def validateTokenLockChain(
        signedBlock: Signed[TokenLockBlock]
      ): F[TokenLockBlockValidationErrorOr[Map[Address, TokenLockNel]]] =
        TokenLockChainValidator
          .validate(signedBlock.tokenLocks)
          .map(_.errorMap[TokenLockBlockValidationError](InvalidTokenLockChain))

      private val addressesLockedAtOrdinal: Map[Address, SnapshotOrdinal] = Map.empty

      private def validateNotLockedAtOrdinal(
        signedBlock: Signed[TokenLockBlock],
        ordinal: SnapshotOrdinal
      ): TokenLockBlockValidationErrorOr[Signed[TokenLockBlock]] =
        signedBlock.value.tokenLocks.toNonEmptyList
          .map(signedTxn =>
            addressesLockedAtOrdinal
              .get(signedTxn.value.source)
              .filter(lockedAt => ordinal >= lockedAt)
              .fold(signedBlock.validNec[TokenLockBlockValidationError])(
                AddressLockedAtOrdinal(signedTxn.value.source, ordinal, _).invalidNec[Signed[TokenLockBlock]]
              )
          )
          .reduce[TokenLockBlockValidationErrorOr[Signed[TokenLockBlock]]](_ *> _)

    }
}

case class TokenLockBlockValidationParams(minSignatureCount: PosInt)

object TokenLockBlockValidationParams {
  val default: TokenLockBlockValidationParams = TokenLockBlockValidationParams(minSignatureCount = 3)
}

@derive(eqv, show)
sealed trait TokenLockBlockValidationError

case class InvalidTokenLockChain(error: TokenLockChainBroken) extends TokenLockBlockValidationError

case class InvalidTokenLock(TokenLockReference: TokenLockReference, error: TokenLockValidationError) extends TokenLockBlockValidationError

case class InvalidSigned(error: SignedValidationError) extends TokenLockBlockValidationError

case class AddressLockedAtOrdinal(address: Address, ordinal: SnapshotOrdinal, lockedAt: SnapshotOrdinal)
    extends TokenLockBlockValidationError
