package org.tessellation.dag.transaction

import cats.data._
import cats.effect.Async
import cats.syntax.all._

import org.tessellation.dag.transaction.TransactionChainValidator.{TransactionChainValidationErrorOr, TransactionNel}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction.{Transaction, TransactionReference}
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive

trait TransactionChainValidator[F[_]] {

  def validate(
    transactions: NonEmptySet[Signed[Transaction]]
  ): F[TransactionChainValidationErrorOr[Map[Address, TransactionNel]]]
}

object TransactionChainValidator {

  def make[F[_]: Async: KryoSerializer]: TransactionChainValidator[F] =
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

  @derive(eqv, show)
  case class TransactionChainBroken(address: Address, referenceNotFound: TransactionReference)

  type TransactionNel = NonEmptyList[Signed[Transaction]]
  type TransactionChainValidationErrorOr[A] = ValidatedNec[TransactionChainBroken, A]
}
