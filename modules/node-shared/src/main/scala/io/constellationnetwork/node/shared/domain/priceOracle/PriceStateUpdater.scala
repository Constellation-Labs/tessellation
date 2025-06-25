package io.constellationnetwork.node.shared.domain.priceOracle

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.DelegatedRewardsConfigProvider
import io.constellationnetwork.node.shared.config.types.EmissionConfigEntry
import io.constellationnetwork.schema.NonNegFraction
import io.constellationnetwork.schema.artifact.PricingUpdate
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.priceOracle.TokenPair.DAG_USD
import io.constellationnetwork.schema.priceOracle.{PriceFraction, PriceRecord, TokenPair}
import io.constellationnetwork.syntax.sortedCollection.sortedMapSyntax

import eu.timepit.refined.cats.posIntCommutativeSemigroup
import eu.timepit.refined.types.numeric.PosInt
import monocle.Monocle.toAppliedFocusOps

trait PriceStateUpdater[F[_]] {

  def updatePriceState(
    lastPriceState: SortedMap[TokenPair, PriceRecord],
    acceptedPricingUpdates: List[PricingUpdate],
    epochProgress: EpochProgress
  ): F[SortedMap[TokenPair, PriceRecord]]

}

object PriceStateUpdater {
  def make[F[_]: Async](
    environment: AppEnvironment,
    delegatedRewardsConfigProvider: DelegatedRewardsConfigProvider
  ): PriceStateUpdater[F] = new PriceStateUpdater[F] {
    override def updatePriceState(
      lastPriceState: SortedMap[TokenPair, PriceRecord],
      acceptedPricingUpdates: List[PricingUpdate],
      epochProgress: EpochProgress
    ): F[SortedMap[TokenPair, PriceRecord]] =
      if (acceptedPricingUpdates.isEmpty) {
        lastPriceState.pure[F]
      } else {
        for {
          emissionConfig <- getEmissionConfig(epochProgress)
          updatedState <- acceptedPricingUpdates
            .groupBy(_.tokenPair)
            .toList
            .traverse {
              case (tokenPair, updates) =>
                aggregateUpdates(NonEmptyList.fromListUnsafe(updates)).flatMap { aggregatedPricingUpdate =>
                  (lastPriceState.get(tokenPair) match {
                    case None =>
                      for {
                        initialCurrentPrice <- initialPrice(tokenPair, emissionConfig)
                        initialUpcomingPrice <- initialPrice(tokenPair, emissionConfig)
                      } yield
                        PriceRecord(
                          currentPrice = initialCurrentPrice,
                          upcomingPrice = initialUpcomingPrice,
                          currentSum = aggregatedPricingUpdate,
                          currentNumEvents = PosInt(1),
                          nextWindowChange = epochProgress |+| EpochProgress(emissionConfig.epochsPerMonth),
                          updatedAt = epochProgress
                        )
                    case Some(lastPriceRecord) =>
                      if (lastPriceRecord.nextWindowChange === epochProgress) {
                        PriceRecord(
                          currentPrice = lastPriceRecord.upcomingPrice,
                          upcomingPrice = lastPriceRecord.currentSum,
                          currentSum = aggregatedPricingUpdate,
                          currentNumEvents = PosInt(1),
                          nextWindowChange = epochProgress |+| EpochProgress(emissionConfig.epochsPerMonth),
                          updatedAt = epochProgress
                        ).pure[F]
                      } else {
                        val newCurrentNumEvents = lastPriceRecord.currentNumEvents |+| PosInt(1)
                        for {
                          newCurrentSum <- aggregateUpdates(
                            NonEmptyList.of(lastPriceRecord.currentSum, aggregatedPricingUpdate),
                            newCurrentNumEvents.some
                          )
                        } yield
                          lastPriceRecord
                            .focus(_.currentSum)
                            .replace(newCurrentSum)
                            .focus(_.currentNumEvents)
                            .replace(newCurrentNumEvents)
                            .focus(_.updatedAt)
                            .replace(epochProgress)
                      }
                  }).map(priceRecord => (tokenPair, priceRecord))
                }
            }
            .map(_.toSortedMap)
            .map(lastPriceState ++ _)

        } yield updatedState
      }

    def getEmissionConfig(epochProgress: EpochProgress): F[EmissionConfigEntry] =
      delegatedRewardsConfigProvider
        .getConfig()
        .emissionConfig
        .get(environment)
        .pure[F]
        .flatMap(Async[F].fromOption(_, new RuntimeException(s"Could not retrieve emission config for env: $environment")))
        .map(f => f(epochProgress))
  }

  def aggregateUpdates[F[_]: Async](updates: NonEmptyList[PricingUpdate], numEvents: Option[PosInt] = None): F[PricingUpdate] =
    if (updates.size == 1) {
      updates.head.pure[F]
    } else {
      val sum = updates.toList.view.map(_.price.value.toBigDecimal).sum
      val n = numEvents.map(_.value).getOrElse(updates.size)
      for {
        value <- NonNegFraction.fromBigDecimal(sum / n)
        price = PriceFraction(tokenPair = updates.head.tokenPair, value = value)
      } yield PricingUpdate(price)
    }

  private def initialPrice[F[_]: Async](tokenPair: TokenPair, emConfig: EmissionConfigEntry): F[PricingUpdate] = {
    val dagPrices = emConfig.dagPrices
    for {
      _ <- if (tokenPair == DAG_USD) tokenPair.pure[F] else Async[F].raiseError(new RuntimeException(s"Unsupported token pair $tokenPair"))
      price <- if (dagPrices.isEmpty) NonNegFraction(0L, 1L) else dagPrices.values.headOption.getOrElse(dagPrices.head._2).pure[F]
    } yield PricingUpdate(PriceFraction(tokenPair, price))
  }

}
