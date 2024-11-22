package io.constellationnetwork.schema

import cats.Order._
import cats.Show
import cats.data.NonEmptySet
import cats.effect.kernel.{Async, Sync}
import cats.syntax.functor._
import cats.syntax.semigroup._

import scala.util.Try

import io.constellationnetwork.ext.cats.data.OrderBasedOrdering
import io.constellationnetwork.ext.codecs._
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.ext.derevo.ordering
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.round.RoundId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher}

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import enumeratum._
import eu.timepit.refined.auto.{autoRefineV, _}
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

  @derive(decoder, encoder, order, show, ordering)
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
    currency: Option[CurrencyId],
    amount: SwapAmount,
    fee: AllowSpendFee,
    parent: AllowSpendReference,
    lastValidEpochProgress: EpochProgress,
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

  @derive(decoder, encoder, show)
  case class AllowSpendBlock(
    roundId: RoundId,
    transactions: NonEmptySet[Signed[AllowSpend]]
  )

  object AllowSpendBlock {
    implicit val roundIdShow: Show[RoundId] = RoundId.shortShow
    implicit val transactionsDecoder: Decoder[NonEmptySet[Signed[AllowSpend]]] =
      NonEmptySetCodec.decoder[Signed[AllowSpend]]
  }
}
