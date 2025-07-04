package io.constellationnetwork.schema

import cats.data.NonEmptyList

import io.constellationnetwork.ext.derevo.ordering
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.priceOracle._
import io.constellationnetwork.schema.swap.{CurrencyId, SwapAmount}
import io.constellationnetwork.schema.tokenLock.TokenLockAmount
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object artifact {
  @derive(decoder, encoder, order, ordering, show)
  sealed trait SharedArtifact

  @derive(decoder, encoder, order, ordering, show)
  case class SpendAction(spendTransactions: NonEmptyList[SpendTransaction]) extends SharedArtifact

  @derive(decoder, encoder, order, ordering, show)
  case class SpendTransaction(
    allowSpendRef: Option[Hash],
    currencyId: Option[CurrencyId],
    amount: SwapAmount,
    source: Address,
    destination: Address
  )

  @derive(decoder, encoder, order, ordering, show)
  case class TokenUnlock(
    tokenLockRef: Hash,
    amount: TokenLockAmount,
    currencyId: Option[CurrencyId],
    source: Address
  ) extends SharedArtifact

  @derive(decoder, encoder, order, ordering, show)
  case class AllowSpendExpiration(
    allowSpendRef: Hash
  ) extends SharedArtifact

  @derive(decoder, encoder, order, ordering, show)
  case class PricingUpdate(price: PriceFraction) extends SharedArtifact {
    def tokenPair: TokenPair = price.tokenPair
  }
}
