package io.constellationnetwork.schema

import cats.effect.kernel.Async
import cats.syntax.functor._

import io.constellationnetwork.ext.derevo.ordering
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.swap.{CurrencyId, SwapAmount}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.types.numeric.PosLong
import io.estatico.newtype.macros.newtype

object artifact {
  @derive(decoder, encoder, order, ordering, show)
  sealed trait SharedArtifact

  @derive(decoder, encoder, order, ordering, show)
  sealed trait SpendTransaction

  @derive(decoder, encoder, order, show)
  @newtype
  case class SpendTransactionFee(value: PosLong)

  @derive(decoder, encoder, order, show)
  @newtype
  case class SpendTransactionReference(hash: Hash)

  @derive(decoder, encoder, order, ordering, show)
  case class SpendAction(input: SpendTransaction, output: SpendTransaction) extends SharedArtifact

  object SpendTransactionReference {
    def of[F[_]: Async](spendTransaction: SpendTransaction)(implicit hasher: Hasher[F]): F[SpendTransactionReference] =
      hasher.hash(spendTransaction).map(SpendTransactionReference(_))
  }
  @derive(decoder, encoder, order, ordering, show)
  case class PendingSpendTransaction(
    fee: SpendTransactionFee,
    lastValidEpochProgress: EpochProgress,
    allowSpendRef: Hash,
    currency: Option[CurrencyId],
    amount: SwapAmount
  ) extends SpendTransaction

  @derive(decoder, encoder, order, ordering, show)
  case class ConcludedSpendTransaction(
    spendTransactionRef: SpendTransactionReference
  ) extends SpendTransaction
}
