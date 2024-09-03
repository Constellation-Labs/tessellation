package io.constellationnetwork.schema

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.transaction.TransactionOrdinal
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.types.numeric.PosLong
import io.estatico.newtype.macros.newtype

object swap {
  @derive(decoder, encoder, order, show)
  @newtype
  case class SwapAmount(value: PosLong)

  @derive(decoder, encoder, order, show)
  @newtype
  case class AllowSpendFee(value: PosLong)

  @derive(decoder, encoder, order, show)
  @newtype
  case class CurrencyId(value: Address)

  @derive(decoder, encoder, order, show)
  case class AllowSpendReference(ordinal: TransactionOrdinal, hash: Hash)

  @derive(decoder, encoder, order, show)
  case class AllowSpend(
    source: Address,
    destination: Address,
    currency: CurrencyId,
    amount: SwapAmount,
    fee: AllowSpendFee,
    parent: AllowSpendReference,
    lastValidOrdinal: SnapshotOrdinal,
    approvers: List[Address]
  )

  @derive(decoder, encoder, order, show)
  @newtype
  case class SwapAMMDataUpdateFee(value: PosLong)

  @derive(decoder, encoder, order, show)
  case class PriceRange(min: SwapAmount, max: SwapAmount)

  @derive(decoder, encoder, order, show)
  @newtype
  case class PoolId(value: Address)

  @derive(decoder, encoder, order, show)
  @newtype
  case class SwapReference(value: Address)

  @derive(decoder, encoder, order, show)
  @newtype
  case class SpendTransactionFee(value: PosLong)

  @derive(decoder, encoder, order, show)
  case class SpendTransaction(
    fee: SpendTransactionFee,
    lastValidOrdinal: SnapshotOrdinal,
    allowSpendRef: Hash,
    currency: Option[CurrencyId],
    amount: SwapAmount
  )

  sealed trait SwapAction

  @derive(decoder, encoder, order, show)
  case class SpendAction(pending: SpendTransaction, transaction: SpendTransaction) extends SwapAction
}
