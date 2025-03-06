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
import io.constellationnetwork.schema.swap.CurrencyId
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

object tokenLock {
  @derive(decoder, encoder, order, show)
  @newtype
  case class TokenLockAmount(value: PosLong)

  object TokenLockAmount {
    implicit def toAmount(amount: TokenLockAmount): Amount = Amount(amount.value)
  }

  @derive(decoder, encoder, show, order, ordering)
  @newtype
  case class TokenLockOrdinal(value: NonNegLong) {
    def next: TokenLockOrdinal = TokenLockOrdinal(value |+| 1L)
  }

  object TokenLockOrdinal {
    val first: TokenLockOrdinal = TokenLockOrdinal(1L)
  }

  @derive(decoder, encoder, order, show)
  case class TokenLockReference(ordinal: TokenLockOrdinal, hash: Hash)

  object TokenLockReference {
    def of(hashedTransaction: Hashed[TokenLock]): TokenLockReference =
      TokenLockReference(hashedTransaction.ordinal, hashedTransaction.hash)

    def of[F[_]: Async: Hasher](signedTransaction: Signed[TokenLock]): F[TokenLockReference] =
      signedTransaction.value.hash.map(TokenLockReference(signedTransaction.ordinal, _))

    val empty: TokenLockReference = TokenLockReference(TokenLockOrdinal(0L), Hash.empty)

    def emptyCurrency[F[_]: Sync: Hasher](currencyAddress: Address): F[TokenLockReference] =
      currencyAddress.value.value.hash.map(emptyCurrency)

    def emptyCurrency(currencyIdentifier: Hash): TokenLockReference =
      TokenLockReference(TokenLockOrdinal(0L), currencyIdentifier)

    implicit object OrderingInstance extends OrderBasedOrdering[TokenLockReference]

  }

  @derive(decoder, encoder, order, show)
  case class TokenLock(
    source: Address,
    amount: TokenLockAmount,
    parent: TokenLockReference,
    currencyId: Option[CurrencyId],
    unlockEpoch: EpochProgress
  ) {
    val ordinal: TokenLockOrdinal = parent.ordinal.next
  }

  object TokenLock {
    implicit object OrderingInstance extends OrderBasedOrdering[TokenLock]
  }

  @derive(encoder)
  case class TokenLockView(
    transaction: TokenLock,
    hash: Hash,
    status: TokenLockStatus
  )

  @derive(eqv, show)
  sealed trait TokenLockStatus extends EnumEntry

  object TokenLockStatus extends Enum[TokenLockStatus] with TokenLockStatusCodecs {
    val values = findValues

    case object Waiting extends TokenLockStatus
  }

  trait TokenLockStatusCodecs {
    implicit val encode: Encoder[TokenLockStatus] = Encoder.encodeString.contramap[TokenLockStatus](_.entryName)
    implicit val decode: Decoder[TokenLockStatus] =
      Decoder.decodeString.emapTry(s => Try(TokenLockStatus.withName(s)))
  }

  @derive(decoder, encoder, show, order)
  case class TokenLockBlock(
    roundId: RoundId,
    tokenLocks: NonEmptySet[Signed[TokenLock]]
  )

  object TokenLockBlock {
    implicit val roundIdShow: Show[RoundId] = RoundId.shortShow
    implicit val transactionsDecoder: Decoder[NonEmptySet[Signed[TokenLock]]] =
      NonEmptySetCodec.decoder[Signed[TokenLock]]
    implicit object OrderingInstance extends OrderBasedOrdering[TokenLockBlock]
  }
}
