package io.constellationnetwork.node.shared.domain.transaction

import cats.data._
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.node.shared.domain.transaction.TransactionChainValidator.{TransactionChainValidationErrorOr, TransactionNel}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.transaction.{Transaction, TransactionReference}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.encoder
import derevo.derive

trait TransactionChainValidator[F[_]] {

  def validate(
    transactions: NonEmptySet[Signed[Transaction]]
  ): F[TransactionChainValidationErrorOr[Map[Address, TransactionNel]]]
}

object TransactionChainValidator {

  def make[F[_]: Async](txHasher: Hasher[F]): TransactionChainValidator[F] =
    new TransactionChainValidator[F] {

      def validate(
        transactions: NonEmptySet[Signed[Transaction]]
      ): F[TransactionChainValidationErrorOr[Map[Address, TransactionNel]]] =
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
        txs: TransactionNel
      ): EitherT[F, TransactionChainBroken, TransactionNel] = {
        val sortedTxs = txs.sortBy(_.ordinal)
        val initChain = NonEmptyList.of(sortedTxs.head).asRight[TransactionChainBroken].toEitherT[F]
        sortedTxs.tail
          .foldLeft(initChain) { (errorOrParents, tx) =>
            errorOrParents.flatMap { parents =>
              implicit val hasher = txHasher
              EitherT(TransactionReference.of(parents.head).map { parentRef =>
                Either.cond(
                  parentRef === tx.parent,
                  tx :: parents,
                  TransactionChainBroken(address, tx.parent)
                )
              })
            }
          }
          .map(_.reverse)
      }
    }

  @derive(eqv, show, encoder)
  case class TransactionChainBroken(address: Address, referenceNotFound: TransactionReference)

  type TransactionNel = NonEmptyList[Signed[Transaction]]
  type TransactionChainValidationErrorOr[A] = ValidatedNec[TransactionChainBroken, A]
}
