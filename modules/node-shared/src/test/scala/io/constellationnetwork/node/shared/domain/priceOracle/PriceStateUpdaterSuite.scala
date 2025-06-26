package io.constellationnetwork.node.shared.domain.priceOracle

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.implicits.{catsSyntaxApplicativeId, catsSyntaxOption}

import scala.collection.immutable.SortedMap

import io.constellationnetwork.env.AppEnvironment.Dev
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.node.shared.config.DefaultDelegatedRewardsConfigProvider
import io.constellationnetwork.node.shared.domain.priceOracle.PriceStateUpdater.aggregateUpdates
import io.constellationnetwork.schema.NonNegFraction
import io.constellationnetwork.schema.artifact.PricingUpdate
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.priceOracle.TokenPair.DAG_USD
import io.constellationnetwork.schema.priceOracle.{PriceFraction, PriceRecord, TokenPair}

import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import weaver.MutableIOSuite

object PriceStateUpdaterSuite extends MutableIOSuite {

  override type Res = Unit

  override def sharedResource: Resource[IO, Res] = ().pure[IO].asResource

  test("single update returns the same update") { _ =>
    for {
      update <- pricingUpdate(10)
      result <- aggregateUpdates(NonEmptyList.one(update))
    } yield expect(result == update)
  }

  test("multiple updates returns average") { _ =>
    for {
      update1 <- pricingUpdate(10)
      update2 <- pricingUpdate(20)
      update3 <- pricingUpdate(30)
      result <- aggregateUpdates(NonEmptyList.of(update1, update2, update3))
    } yield expect(result.price.value.toBigDecimal == 20.0)
  }

  test("custom numEvents affects average calculation") { _ =>
    for {
      update1 <- pricingUpdate(10)
      update2 <- pricingUpdate(20)
      update3 <- pricingUpdate(30)
      result <- aggregateUpdates(NonEmptyList.of(update1, update2, update3), Some(PosInt(2)))
    } yield expect(result.price.value.toBigDecimal == 30.0) // (10 + 20 + 30) / 2 = 30
  }

  test("handles zero values correctly") { _ =>
    for {
      update1 <- pricingUpdate(0)
      update2 <- pricingUpdate(0)
      result <- aggregateUpdates(NonEmptyList.of(update1, update2))
    } yield expect(result.price.value.toBigDecimal == 0.0)
  }

  test("preserves token pair from input updates") { _ =>
    for {
      update1 <- pricingUpdate(10)
      update2 <- pricingUpdate(20)
      result <- aggregateUpdates(NonEmptyList.of(update1, update2))
    } yield expect(result.tokenPair == DAG_USD)
  }

  test("empty updates list returns unchanged state") { _ =>
    val updater = PriceStateUpdater.make[IO](Dev, DefaultDelegatedRewardsConfigProvider)
    val initialState = SortedMap(
      DAG_USD -> PriceRecord(
        currentPrice = PricingUpdate(PriceFraction(DAG_USD, NonNegFraction.unsafeFrom(1, 1))),
        upcomingPrice = PricingUpdate(PriceFraction(DAG_USD, NonNegFraction.unsafeFrom(2, 2))),
        currentSum = PricingUpdate(PriceFraction(DAG_USD, NonNegFraction.unsafeFrom(3, 3))),
        currentNumEvents = PosInt(1),
        nextWindowChange = EpochProgress(NonNegLong(10)),
        updatedAt = EpochProgress(NonNegLong(0))
      )
    )

    for {
      result <- updater.updatePriceState(initialState, List.empty, EpochProgress(NonNegLong(1)))
    } yield expect(result == initialState)
  }

  test("new token pair creates initial price record") { _ =>
    val updater = PriceStateUpdater.make[IO](Dev, DefaultDelegatedRewardsConfigProvider)
    val initialState = SortedMap.empty[TokenPair, PriceRecord]

    for {
      initialCurrentPrice <- invPricingUpdate(0.04)
      initialUpcomingPrice <- invPricingUpdate(0.04)
      update <- invPricingUpdate(4)
      result <- updater.updatePriceState(initialState, List(update), EpochProgress(NonNegLong(1)))
      record <- result.get(DAG_USD).liftTo[IO](new Exception("No price record found"))
    } yield
      expect.all(
        record.currentPrice.price.value.toBigDecimal == initialCurrentPrice.price.value.toBigDecimal,
        record.upcomingPrice.price.value.toBigDecimal == initialUpcomingPrice.price.value.toBigDecimal,
        record.currentSum == update,
        record.currentNumEvents == PosInt(1),
        record.nextWindowChange == EpochProgress(NonNegLong(11)),
        record.updatedAt == EpochProgress(NonNegLong(1))
      )
  }

  test("existing token pair updates price record") { _ =>
    val updater = PriceStateUpdater.make[IO](Dev, DefaultDelegatedRewardsConfigProvider)

    for {
      currentPrice <- invPricingUpdate(10)
      upcomingPrice <- invPricingUpdate(20)
      currentSum <- invPricingUpdate(40)
      initialState = SortedMap(
        DAG_USD -> PriceRecord(
          currentPrice = currentPrice,
          upcomingPrice = upcomingPrice,
          currentSum = currentSum,
          currentNumEvents = PosInt(1),
          nextWindowChange = EpochProgress(NonNegLong(10)),
          updatedAt = EpochProgress(NonNegLong(0))
        )
      )
      update <- invPricingUpdate(50)
      result <- updater.updatePriceState(initialState, List(update), EpochProgress(NonNegLong(1)))
      record <- result.get(DAG_USD).liftTo[IO](new Exception("No price record found"))
    } yield
      expect.all(
        record.currentPrice == currentPrice,
        record.upcomingPrice == upcomingPrice,
        record.currentSum == PricingUpdate(PriceFraction(DAG_USD, NonNegFraction.unsafeFrom(2250000, 100000000))),
        record.currentNumEvents == PosInt(2),
        record.nextWindowChange == EpochProgress(NonNegLong(10)),
        record.updatedAt == EpochProgress(NonNegLong(1))
      )
  }

  test("window change updates price record correctly") { _ =>
    val updater = PriceStateUpdater.make[IO](Dev, DefaultDelegatedRewardsConfigProvider)

    for {
      currentPrice <- invPricingUpdate(10)
      upcomingPrice <- invPricingUpdate(20)
      currentSum <- invPricingUpdate(30)
      initialState = SortedMap(
        DAG_USD -> PriceRecord(
          currentPrice = currentPrice,
          upcomingPrice = upcomingPrice,
          currentSum = currentSum,
          currentNumEvents = PosInt(1),
          nextWindowChange = EpochProgress(NonNegLong(10)),
          updatedAt = EpochProgress(NonNegLong(0))
        )
      )
      update <- invPricingUpdate(40)
      result <- updater.updatePriceState(initialState, List(update), EpochProgress(NonNegLong(10)))
      record <- result.get(DAG_USD).liftTo[IO](new Exception("No price record found"))
    } yield
      expect.all(
        record.currentPrice == upcomingPrice,
        record.upcomingPrice == currentSum,
        record.currentSum == update,
        record.currentNumEvents == PosInt(1),
        record.nextWindowChange == EpochProgress(NonNegLong(20)),
        record.updatedAt == EpochProgress(NonNegLong(10))
      )
  }

  private def pricingUpdate(n: Long): IO[PricingUpdate] =
    NonNegFraction(n, 1L).map(value => PricingUpdate(price = PriceFraction(DAG_USD, value)))

  private def invPricingUpdate(n: BigDecimal): IO[PricingUpdate] =
    NonNegFraction.fromBigDecimal(BigDecimal(1) / n).map(value => PricingUpdate(price = PriceFraction(DAG_USD, value)))
}
