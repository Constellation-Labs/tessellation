package org.tessellation.node.shared.domain.statechannel

import cats.syntax.option._

import scala.collection.immutable.SortedMap

import org.tessellation.currency.schema.currency.SnapshotFee
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.balance.Balance

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.SimpleIOSuite

object FeeCalculatorSuite extends SimpleIOSuite {

  val snasphotOrdinal9 = SnapshotOrdinal.unsafeApply(9L)
  val snasphotOrdinal10 = SnapshotOrdinal.unsafeApply(10L)
  val snasphotOrdinal20 = SnapshotOrdinal.unsafeApply(20L)

  val config10 =
    FeeCalculatorConfig(baseFee = 100000L, stakingWeight = BigDecimal(0.0000002d), computationalCost = 1L, proWeight = BigDecimal(0.5d))
  val config20 =
    FeeCalculatorConfig(baseFee = 500000L, stakingWeight = BigDecimal(0.0000002d), computationalCost = 1L, proWeight = BigDecimal(0.5d))

  val configs = SortedMap[SnapshotOrdinal, FeeCalculatorConfig](
    SnapshotOrdinal.MinValue -> FeeCalculatorConfig.noFee,
    snasphotOrdinal10 -> config10,
    snasphotOrdinal20 -> config20
  )

  test("zero fee config should result in fee equal to 0") {
    val calculator = FeeCalculator.make(configs)

    val expected: SnapshotFee = SnapshotFee.MinValue

    calculator
      .calculateRecommendedFee(SnapshotOrdinal.MinValue.some, 0L)(staked = Balance.empty, sizeKb = 10)
      .map(actual => expect.eql(expected, actual))
  }

  test("correct fee should be calculated for non zero config") {
    val calculator = FeeCalculator.make(configs)

    val expected: SnapshotFee = SnapshotFee(1000000L)

    calculator
      .calculateRecommendedFee(snasphotOrdinal10.some, 0L)(staked = Balance.empty, sizeKb = 10)
      .map(actual => expect.eql(expected, actual))
  }

  test("a closest lesser or equal config should be picked for fee calculation") {
    val calculator = FeeCalculator.make(configs)

    val expected9: SnapshotFee = SnapshotFee.MinValue
    val expected10: SnapshotFee = SnapshotFee(1000000L)

    for {
      actual9 <- calculator.calculateRecommendedFee(snasphotOrdinal9.some, 0L)(staked = Balance.empty, sizeKb = 10)
      actual10 <- calculator.calculateRecommendedFee(snasphotOrdinal10.some, 0L)(staked = Balance.empty, sizeKb = 10)
      actual = (actual9, actual10)
    } yield expect.eql((expected9, expected10), actual)
  }

  test("proportionally bigger fee should be calculated for greater size") {
    val calculator = FeeCalculator.make(configs)

    val expected: SnapshotFee = SnapshotFee(2000000L)

    calculator
      .calculateRecommendedFee(snasphotOrdinal10.some, 0L)(staked = Balance.empty, sizeKb = 20)
      .map(actual => expect.eql(expected, actual))
  }

  test("tip should be added to the fee") {
    val calculator = FeeCalculator.make(configs)
    val tip = NonNegLong(100L)

    val expected: SnapshotFee = SnapshotFee(1000100L)

    calculator
      .calculateRecommendedFee(snasphotOrdinal10.some, 0L)(staked = Balance.empty, sizeKb = 10, tip = tip)
      .map(actual => expect.eql(expected, actual))
  }

  test("positive staking balance should decrease the calculated fee") {
    val calculator = FeeCalculator.make(configs)
    val staked = Balance(5000000L)

    val expected: SnapshotFee = SnapshotFee(500000L)

    calculator
      .calculateRecommendedFee(snasphotOrdinal10.some, 0L)(staked = staked, sizeKb = 10)
      .map(actual => expect.eql(expected, actual))
  }

  test("positive pro score should decrease the calculated fee") {
    val calculator = FeeCalculator.make(configs)

    val expected: SnapshotFee = SnapshotFee(800000L)

    calculator
      .calculateRecommendedFee(snasphotOrdinal10.some, 0L)(staked = Balance.empty, sizeKb = 10, proScore = 0.5d)
      .map(actual => expect.eql(expected, actual))
  }

  test(
    "when snapshot ordinal is known then worst case scenario e.i. highest fee should be calculated based on a range of configs [ordinal, ordinal + delay]"
  ) {
    val calculator = FeeCalculator.make(configs)

    val expected: SnapshotFee = SnapshotFee(1000000L)

    calculator
      .calculateRecommendedFee(SnapshotOrdinal.MinValue.some, 10L)(staked = Balance.empty, sizeKb = 10)
      .map(actual => expect.eql(expected, actual))
  }

  test(
    "when snapshot ordinal is not known then a worst case scenario e.i. highest fee should be calculated based on all available configs"
  ) {
    val calculator = FeeCalculator.make(configs)

    val expected: SnapshotFee = SnapshotFee(5000000L)

    calculator
      .calculateRecommendedFee(None, 10L)(staked = Balance.empty, sizeKb = 10)
      .map(actual => expect.eql(expected, actual))
  }
}
