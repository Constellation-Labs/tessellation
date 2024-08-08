package org.tessellation.schema

import cats.Order._
import cats.effect.kernel.{Async, Sync}
import cats.syntax.functor._
import cats.syntax.semigroup._

import scala.util.Try

import org.tessellation.ext.cats.data.OrderBasedOrdering
import org.tessellation.ext.crypto._
import org.tessellation.ext.derevo.ordering
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hashed, Hasher}

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import enumeratum._
import eu.timepit.refined.auto.{autoRefineV, autoUnwrap, _}
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import io.circe.{Decoder, Encoder}
import io.estatico.newtype.macros.newtype

object swap {
  @derive(decoder, encoder, order, show)
  @newtype
  case class SwapAmount(value: PosLong)

  object SwapAmount {
    implicit def toAmount(amount: SwapAmount): Amount = Amount(amount.value)
  }

  @derive(decoder, encoder, order, show)
  @newtype
  case class AllowSpendFee(value: PosLong)

  object AllowSpendFee {
    implicit def toAmount(fee: AllowSpendFee): Amount = Amount(fee.value)
  }

  @derive(decoder, encoder, order, show)
  @newtype
  case class CurrencyId(value: Address)

  @derive(decoder, encoder, show, order, ordering)
  @newtype
  case class AllowSpendOrdinal(value: NonNegLong) {
    def next: AllowSpendOrdinal = AllowSpendOrdinal(value |+| 1L)
  }

  object AllowSpendOrdinal {
    val first: AllowSpendOrdinal = AllowSpendOrdinal(1L)
  }

  @derive(decoder, encoder, order, show)
  case class AllowSpendReference(ordinal: AllowSpendOrdinal, hash: Hash)

  object AllowSpendReference {
    def of(hashedTransaction: Hashed[AllowSpend]): AllowSpendReference =
      AllowSpendReference(hashedTransaction.ordinal, hashedTransaction.hash)

    def of[F[_]: Async: Hasher](signedTransaction: Signed[AllowSpend]): F[AllowSpendReference] =
      signedTransaction.value.hash.map(AllowSpendReference(signedTransaction.ordinal, _))

    val empty: AllowSpendReference = AllowSpendReference(AllowSpendOrdinal(0L), Hash.empty)

    def emptyCurrency[F[_]: Sync: Hasher](currencyAddress: Address): F[AllowSpendReference] =
      currencyAddress.value.value.hash.map(emptyCurrency(_))

    def emptyCurrency(currencyIdentifier: Hash): AllowSpendReference =
      AllowSpendReference(AllowSpendOrdinal(0L), currencyIdentifier)

  }

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
  ) {
    val ordinal: AllowSpendOrdinal = parent.ordinal.next
  }

  object AllowSpend {
    implicit object OrderingInstance extends OrderBasedOrdering[AllowSpend]
  }

  @derive(encoder)
  case class AllowSpendView(
    transaction: AllowSpend,
    hash: Hash,
    status: AllowSpendStatus
  )

  @derive(eqv, show)
  sealed trait AllowSpendStatus extends EnumEntry

  object AllowSpendStatus extends Enum[AllowSpendStatus] with AllowSpendStatusCodecs {
    val values = findValues

    case object Waiting extends AllowSpendStatus
  }

  trait AllowSpendStatusCodecs {
    implicit val encode: Encoder[AllowSpendStatus] = Encoder.encodeString.contramap[AllowSpendStatus](_.entryName)
    implicit val decode: Decoder[AllowSpendStatus] =
      Decoder.decodeString.emapTry(s => Try(AllowSpendStatus.withName(s)))
  }

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
