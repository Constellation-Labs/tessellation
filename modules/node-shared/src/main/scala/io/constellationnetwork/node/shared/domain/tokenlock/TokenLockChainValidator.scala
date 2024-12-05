package io.constellationnetwork.node.shared.domain.tokenlock

import cats.data._
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.node.shared.domain.tokenlock.TokenLockChainValidator.{TokenLockChainValidationErrorOr, TokenLockNel}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.tokenLock.{TokenLock, TokenLockReference}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive

trait TokenLockChainValidator[F[_]] {

  def validate(
    transactions: NonEmptySet[Signed[TokenLock]]
  ): F[TokenLockChainValidationErrorOr[Map[Address, TokenLockNel]]]
}

object TokenLockChainValidator {

  def make[F[_]: Async: Hasher]: TokenLockChainValidator[F] =
    new TokenLockChainValidator[F] {

      def validate(
        transactions: NonEmptySet[Signed[TokenLock]]
      ): F[TokenLockChainValidationErrorOr[Map[Address, TokenLockNel]]] =
        transactions.toNonEmptyList
          .groupBy(_.value.source)
          .toList
          .traverse {
            case (address, txs) =>
              validateChainForSingleAddress(address, txs)
                .map(chainedTxs => address -> chainedTxs)
                .value
                .map(_.toValidatedNec)
          }
          .map(_.foldMap(_.map(Chain(_))))
          .map(_.map(_.toList.toMap))

      private def validateChainForSingleAddress(
        address: Address,
        txs: TokenLockNel
      ): EitherT[F, TokenLockChainBroken, TokenLockNel] = {
        val sortedTxs = txs.sortBy(_.ordinal)
        val initChain = NonEmptyList.of(sortedTxs.head).asRight[TokenLockChainBroken].toEitherT[F]
        sortedTxs.tail
          .foldLeft(initChain) { (errorOrParents, tx) =>
            errorOrParents.flatMap { parents =>
              EitherT(TokenLockReference.of(parents.head).map { parentRef =>
                Either.cond(
                  parentRef === tx.parent,
                  tx :: parents,
                  TokenLockChainBroken(address, tx.parent)
                )
              })
            }
          }
          .map(_.reverse)
      }
    }

  @derive(eqv, show)
  case class TokenLockChainBroken(address: Address, referenceNotFound: TokenLockReference)

  type TokenLockNel = NonEmptyList[Signed[TokenLock]]
  type TokenLockChainValidationErrorOr[A] = ValidatedNec[TokenLockChainBroken, A]
}
