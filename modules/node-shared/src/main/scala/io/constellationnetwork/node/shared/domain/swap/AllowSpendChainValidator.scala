package io.constellationnetwork.node.shared.domain.swap

import cats.data._
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.node.shared.domain.swap.AllowSpendChainValidator.{AllowSpendChainValidationErrorOr, AllowSpendNel}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.swap.{AllowSpend, AllowSpendReference}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive

trait AllowSpendChainValidator[F[_]] {

  def validate(
    transactions: NonEmptySet[Signed[AllowSpend]]
  ): F[AllowSpendChainValidationErrorOr[Map[Address, AllowSpendNel]]]
}

object AllowSpendChainValidator {

  def make[F[_]: Async: Hasher]: AllowSpendChainValidator[F] =
    new AllowSpendChainValidator[F] {

      def validate(
        transactions: NonEmptySet[Signed[AllowSpend]]
      ): F[AllowSpendChainValidationErrorOr[Map[Address, AllowSpendNel]]] =
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
        txs: AllowSpendNel
      ): EitherT[F, AllowSpendChainBroken, AllowSpendNel] = {
        val sortedTxs = txs.sortBy(_.ordinal)
        val initChain = NonEmptyList.of(sortedTxs.head).asRight[AllowSpendChainBroken].toEitherT[F]
        sortedTxs.tail
          .foldLeft(initChain) { (errorOrParents, tx) =>
            errorOrParents.flatMap { parents =>
              EitherT(AllowSpendReference.of(parents.head).map { parentRef =>
                Either.cond(
                  parentRef === tx.parent,
                  tx :: parents,
                  AllowSpendChainBroken(address, tx.parent)
                )
              })
            }
          }
          .map(_.reverse)
      }
    }

  @derive(eqv, show)
  case class AllowSpendChainBroken(address: Address, referenceNotFound: AllowSpendReference)

  type AllowSpendNel = NonEmptyList[Signed[AllowSpend]]
  type AllowSpendChainValidationErrorOr[A] = ValidatedNec[AllowSpendChainBroken, A]
}
