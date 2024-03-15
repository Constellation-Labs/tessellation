package org.tessellation.node.shared.domain.statechannel

import java.math.{MathContext, RoundingMode}

import cats.MonadThrow
import cats.data.{NonEmptyList, NonEmptySet}
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import org.tessellation.currency.schema.currency.SnapshotFee
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.balance.Balance

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Interval
import eu.timepit.refined.types.numeric.{NonNegBigDecimal, NonNegInt, NonNegLong}

case class FeeCalculatorConfig(
  baseFee: NonNegLong,
  stakingWeight: NonNegBigDecimal,
  computationalCost: NonNegLong,
  proWeight: NonNegBigDecimal
)

object FeeCalculatorConfig {
  val noFee: FeeCalculatorConfig =
    FeeCalculatorConfig(baseFee = 0L, stakingWeight = BigDecimal(0.0), computationalCost = 1L, proWeight = BigDecimal(0.0))

  def getByOrdinal(configs: SortedMap[SnapshotOrdinal, FeeCalculatorConfig])(ordinal: SnapshotOrdinal): FeeCalculatorConfig =
    configs
      .rangeTo(ordinal)
      .lastOption
      .map { case (_, config) => config }
      .getOrElse(FeeCalculatorConfig.noFee)
}

sealed trait FeeCalculator[F[_]] {
  def calculateRecommendedFee(
    maybeOrdinal: Option[SnapshotOrdinal],
    delay: NonNegLong = 0L
  )(staked: Balance, sizeKb: NonNegInt, tip: NonNegLong = 0L, proScore: FeeCalculator.ProScore = 0.0d): F[SnapshotFee]
}

object FeeCalculator {
  def make[F[_]: MonadThrow](configs: SortedMap[SnapshotOrdinal, FeeCalculatorConfig]): FeeCalculator[F] =
    new FeeCalculator[F] {
      private val allConfigs: NonEmptyList[FeeCalculatorConfig] =
        NonEmptyList
          .fromList(configs.values.toList)
          .getOrElse(NonEmptyList.one(FeeCalculatorConfig.noFee))

      private def getConfig = FeeCalculatorConfig.getByOrdinal(configs)(_)

      def calculateRecommendedFee(
        maybeOrdinal: Option[SnapshotOrdinal],
        delay: NonNegLong
      )(staked: Balance, sizeKb: NonNegInt, tip: NonNegLong, proScore: ProScore): F[SnapshotFee] =
        maybeOrdinal
          .fold(allConfigs) { ordinal =>
            NonEmptySet
              .of(ordinal, ordinal.plus(delay))
              .toNonEmptyList
              .map(getConfig)
          }
          .traverse(calculate(_)(staked, sizeKb, tip, proScore))
          .map(_.maximum)
    }

  private def calculate[F[_]: MonadThrow](
    config: FeeCalculatorConfig
  )(staked: Balance, sizeKb: NonNegInt, tip: NonNegLong, proScore: ProScore): F[SnapshotFee] = {
    val FeeCalculatorConfig(baseFee, stakingWeight, computationalCost, proWeight) = config
    val workAmount: BigDecimal = BigDecimal(sizeKb) * BigDecimal(computationalCost)

    val workMultiplier: BigDecimal =
      BigDecimal(1) / (BigDecimal(1) + BigDecimal(staked.value) * stakingWeight + BigDecimal(proScore) * proWeight)

    val roundUp = new MathContext(0, RoundingMode.UP)
    val fee = (BigDecimal(baseFee) * workAmount * workMultiplier + BigDecimal(tip)).round(roundUp).toBigInt

    Either
      .cond(fee.isValidLong, fee.longValue, "calculated fee exceeded long max value")
      .flatMap(NonNegLong.from)
      .map(SnapshotFee(_))
      .leftMap(msg => new IllegalStateException(s"Critical error during snapshot fee calculation: $msg!"))
      .liftTo[F]
  }

  type ProScore = Double Refined Interval.Closed[0.0d, 1.0d]
}
