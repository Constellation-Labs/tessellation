package io.constellationnetwork.schema

import cats.Order._
import cats.effect.Async
import cats.syntax.functor._
import cats.syntax.semigroup._

import io.constellationnetwork.ext.crypto.RefinedHasher
import io.constellationnetwork.ext.derevo.ordering
import io.constellationnetwork.schema.artifact.PricingUpdate
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher}

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia._
import derevo.derive
import eu.timepit.refined.auto.autoRefineV
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import io.circe.KeyDecoder
import io.estatico.newtype.macros.newtype

object priceOracle {

  @derive(decoder, encoder, keyEncoder, show, order, ordering)
  sealed trait TokenId

  object TokenId {
    implicit val keyDecoder: KeyDecoder[TokenId] = new KeyDecoder[TokenId] {
      def apply(key: String): Option[TokenId] =
        CryptoToken.keyDecoder(key).orElse(FiatToken.keyDecoder(key))
    }
  }

  @derive(decoder, encoder, keyEncoder, show, order, ordering)
  sealed trait CryptoToken extends TokenId
  @derive(decoder, encoder, keyEncoder, show, order, ordering)
  case object DAG extends CryptoToken

  object CryptoToken {
    implicit val keyDecoder: KeyDecoder[CryptoToken] = new KeyDecoder[CryptoToken] {
      def apply(key: String): Option[CryptoToken] = key match {
        case "DAG" => Some(DAG)
        case _     => None
      }
    }
  }

  @derive(decoder, encoder, keyEncoder, show, order, ordering)
  sealed trait FiatToken extends TokenId
  @derive(decoder, encoder, keyEncoder, show, order, ordering)
  case object USD extends FiatToken

  object FiatToken {
    implicit val keyDecoder: KeyDecoder[FiatToken] = new KeyDecoder[FiatToken] {
      def apply(key: String): Option[FiatToken] = key match {
        case "USD" => Some(USD)
        case _     => None
      }
    }
  }

  /** An asset pair representation.
    *
    * Base asset: This is the first asset in the pair. It's the one you are buying or selling.
    *
    * Quote asset: This is the second asset in the pair. It shows how much of this asset is needed to buy one unit of the base asset.
    */
  @derive(decoder, encoder, keyEncoder, keyDecoder, show, order, ordering)
  case class TokenPair(base: TokenId, quote: TokenId)

  object TokenPair {
    val DAG_USD = TokenPair(DAG, USD)
  }

  @derive(decoder, encoder, show)
  case class PriceFraction(tokenPair: TokenPair, value: NonNegFraction)

  @derive(eqv, show, encoder, decoder)
  case class PriceRecord(
    currentPrice: PricingUpdate,
    upcomingPrice: PricingUpdate,
    currentSum: PricingUpdate,
    currentNumEvents: PosInt,
    nextWindowChange: EpochProgress,
    updatedAt: EpochProgress
  )
}
