package io.constellationnetwork.schema

import cats.effect.IO
import cats.syntax.applicative._

import io.constellationnetwork.schema.AmountOps.{AmountOps => AmountOpsClass, BigDecimalOps}
import io.constellationnetwork.schema.balance.Amount

import eu.timepit.refined.types.numeric.NonNegLong
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object AmountOpsSuite extends SimpleIOSuite with Checkers {

  // Use smaller values to avoid overflow when adding two large longs
  val nonNegativeLongGen: Gen[Long] = Gen.chooseNum(0L, 1000000L)
  val positiveAmountGen: Gen[Amount] = nonNegativeLongGen.map(l => Amount(NonNegLong.unsafeFrom(l)))
  val anyDecimalGen: Gen[BigDecimal] = Gen.chooseNum(-1000.0, 1000.0).map(BigDecimal(_))
  val positiveDecimalGen: Gen[BigDecimal] = Gen.posNum[Double].map(BigDecimal(_))
  val scaleGen: Gen[Int] = Gen.chooseNum(0, 10)

  test("plus should add two amounts") {
    // Use smaller values to avoid overflow
    val smallPositiveAmountGen = Gen.chooseNum(0L, 1000L).map(l => Amount(NonNegLong.unsafeFrom(l)))
    val gen = for {
      a1 <- smallPositiveAmountGen
      a2 <- smallPositiveAmountGen
    } yield (a1, a2)

    forall(gen) { pair =>
      val (a1, a2) = pair
      for {
        result <- IO.fromEither(a1.plus(a2))
        expected = Amount(NonNegLong.unsafeFrom(a1.value.value + a2.value.value))
      } yield expect.eql(expected, result)
    }
  }

  test("safeDivideBy should handle division by zero") {
    forall(positiveAmountGen) { amount =>
      for {
        zeroAmount <- Amount.empty.pure[IO]
        result = amount.safeDivideBy(zeroAmount)
      } yield expect.eql(BigDecimal(0), result)
    }
  }

  test("safeDivideBy should perform normal division when divisor is not zero") {
    // Use a fixed non-empty amount for the divisor to avoid discarding too many values
    val nonEmptyAmount = Amount(NonNegLong.unsafeFrom(100L))

    forall(positiveAmountGen) { a1 =>
      for {
        result <- new AmountOpsClass(a1).safeDivideBy(nonEmptyAmount).pure[IO]
        expected = BigDecimal(a1.value.value) / BigDecimal(nonEmptyAmount.value.value)
      } yield expect.eql(expected, result)
    }
  }

  test("toBigDecimal should convert amount to BigDecimal") {
    forall(positiveAmountGen) { amount =>
      for {
        result <- new AmountOpsClass(amount).toBigDecimal.pure[IO]
        expected = BigDecimal(amount.value.value)
      } yield expect.eql(expected, result)
    }
  }

  test("times should multiply amount by scalar") {
    val gen = for {
      amount <- positiveAmountGen
      scalar <- anyDecimalGen
    } yield (amount, scalar)

    forall(gen) { pair =>
      val (amount, scalar) = pair
      for {
        result <- new AmountOpsClass(amount).times(scalar).pure[IO]
        expected = BigDecimal(amount.value.value) * scalar
      } yield expect.eql(expected, result)
    }
  }

  test("rounded should round to the specified scale") {
    val gen = for {
      amount <- positiveAmountGen
      scale <- scaleGen
    } yield (amount, scale)

    forall(gen) { pair =>
      val (amount, scale) = pair
      for {
        result <- new AmountOpsClass(amount).roundedHalfUp(scale).pure[IO]
        expected = BigDecimal(amount.value.value).setScale(scale, scala.math.BigDecimal.RoundingMode.HALF_UP)
      } yield expect.eql(expected, result)
    }
  }

  test("toAmount should convert positive BigDecimal to Amount") {
    forall(positiveDecimalGen) { value =>
      for {
        result <- new BigDecimalOps(value).toAmount[IO]
        expected = Amount(NonNegLong.unsafeFrom(value.setScale(0, scala.math.BigDecimal.RoundingMode.HALF_UP).longValue))
      } yield expect.eql(expected, result)
    }
  }

  test("toAmount should return empty Amount for non-positive BigDecimal") {
    // Use a fixed non-positive value to avoid discarding too many values
    val nonPositiveValue = BigDecimal(-1.0)

    for {
      result <- new BigDecimalOps(nonPositiveValue).toAmount[IO]
    } yield expect.eql(Amount.empty, result)
  }
}
