package io.constellationnetwork.schema

import cats.effect.IO

import io.constellationnetwork.schema.NonNegFraction

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.cats.refTypeShow
import eu.timepit.refined.numeric.{NonNegative, Positive}
import eu.timepit.refined.refineMV
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import org.scalacheck.Gen
import org.scalacheck.Prop._
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object NonNegFractionSuite extends SimpleIOSuite with Checkers {

  val nonNegLongGen: Gen[Long Refined NonNegative] =
    Gen.chooseNum(0L, Long.MaxValue).map(NonNegLong.unsafeFrom)

  val posLongGen: Gen[Long Refined Positive] =
    Gen.chooseNum(1L, Long.MaxValue).map(PosLong.unsafeFrom)

  val nonNegFractionGen: Gen[NonNegFraction] = for {
    num <- nonNegLongGen
    denom <- posLongGen
  } yield NonNegFraction(num, denom)

  val nonNegDoubleGen: Gen[Double] =
    Gen.chooseNum(0.0, Double.MaxValue)

  test("NonNegFraction.unsafeFrom creates expected fractions") {
    forall(Gen.chooseNum(0L, 10000L).flatMap(a => Gen.chooseNum(1L, 10000L).map(b => (a, b)))) {
      case (num, denom) =>
        val fraction = NonNegFraction.unsafeFrom(num, denom)
        expect(fraction.numerator.value == num) &&
        expect(fraction.denominator.value == denom)
    }
  }

  test("NonNegFraction.apply returns Right for valid inputs") {
    forall(Gen.chooseNum(0L, 10000L).flatMap(a => Gen.chooseNum(1L, 10000L).map(b => (a, b)))) {
      case (num, denom) =>
        val result = NonNegFraction[Either[Throwable, *]](num, denom)
        expect(result.isRight) &&
        result.map { fraction =>
          expect(fraction.numerator.value == num) &&
          expect(fraction.denominator.value == denom)
        }.getOrElse(success)
    }
  }

  test("NonNegFraction.apply returns Left for negative numerator") {
    forall(Gen.chooseNum(Long.MinValue, -1L).flatMap(a => Gen.chooseNum(1L, 10000L).map(b => (a, b)))) {
      case (num, denom) =>
        val result = NonNegFraction[Either[Throwable, *]](num, denom)
        expect(result.isLeft)
    }
  }

  test("NonNegFraction.apply returns Left for non-positive denominator") {
    forall(Gen.chooseNum(0L, 10000L).flatMap(a => Gen.chooseNum(Long.MinValue, 0L).map(b => (a, b)))) {
      case (num, denom) =>
        val result = NonNegFraction[Either[Throwable, *]](num, denom)
        expect(result.isLeft)
    }
  }

  test("fromBigDecimal creates a fraction with 8 digits of precision") {
    val testCases = List(
      BigDecimal("2.0") -> (200000000L, 100000000L),
      BigDecimal("2.000000001") -> (200000000L, 100000000L), // Truncated
      BigDecimal("1.12345678") -> (112345678L, 100000000L),
      BigDecimal("1.123456789") -> (112345678L, 100000000L) // Truncated
    )

    IO.parSequenceN(4)(testCases.map {
      case (input, (expectedNum, expectedDenom)) =>
        NonNegFraction.fromBigDecimal[IO](input).map { fraction =>
          expect(fraction.numerator.value == expectedNum) &&
          expect(fraction.denominator.value == expectedDenom)
        }
    }).map(_.reduce(_ && _))
  }

  test("fromBigDecimal rejects negative inputs") {
    forall(Gen.chooseNum(Double.MinValue, -0.000000001)) { negValue =>
      val result = NonNegFraction.fromBigDecimal[Either[Throwable, *]](BigDecimal(negValue))
      expect(result.isLeft)
    }
  }

  test("fromDouble creates a fraction with 8 digits of precision") {
    val testCases = List(
      2.0 -> (200000000L, 100000000L),
      2.000000001 -> (200000000L, 100000000L), // Truncated
      1.12345678 -> (112345678L, 100000000L),
      1.123456789 -> (112345678L, 100000000L) // Truncated
    )

    IO.parSequenceN(4)(testCases.map {
      case (input, (expectedNum, expectedDenom)) =>
        NonNegFraction.fromDouble[IO](input).map { fraction =>
          expect(fraction.numerator.value == expectedNum) &&
          expect(fraction.denominator.value == expectedDenom)
        }
    }).map(_.reduce(_ && _))
  }

  test("fromDouble rejects negative inputs") {
    forall(Gen.chooseNum(Double.MinValue, -0.000000001)) { negValue =>
      val result = NonNegFraction.fromDouble[Either[Throwable, *]](negValue)
      expect(result.isLeft)
    }
  }

  test("fromPercentage correctly converts percentages to fractions") {
    forall(Gen.chooseNum(0.0, 100.0)) { percentage =>
      val result = NonNegFraction.fromPercentage[Either[Throwable, *]](percentage)
      result.map { fraction =>
        val expected = (percentage * 1_000_000L).toLong
        expect(fraction.numerator.value == expected) &&
        expect(fraction.denominator.value == 100_000_000L)
      }.getOrElse(success)
    }
  }

  test("addition preserves non-negativity") {
    forall(nonNegFractionGen.flatMap(a => nonNegFractionGen.map(b => (a, b)))) {
      case (a, b) =>
        val result = a.+[Either[Throwable, *]](b)
        expect(result.isRight) &&
        result.map { sum =>
          expect(sum.numerator.value >= 0) &&
          expect(sum.denominator.value > 0)
        }.getOrElse(success)
    }
  }

  test("subtraction fails if result would be negative") {
    forall(nonNegFractionGen.flatMap(a => nonNegFractionGen.map(b => (a, b)))) {
      case (a, b) =>
        val aValue = BigInt(a.numerator.value) * BigInt(b.denominator.value)
        val bValue = BigInt(b.numerator.value) * BigInt(a.denominator.value)

        val result = a.-[Either[Throwable, *]](b)
        if (aValue < bValue) { expect(result.isLeft) }
        else {
          expect(result.isRight) &&
          result.map { diff =>
            expect(diff.numerator.value >= 0) &&
            expect(diff.denominator.value > 0)
          }.getOrElse(success)
        }
    }
  }

  // Test for toBigDecimal
  test("toBigDecimal produces correct decimal representation") {
    forall(nonNegLongGen.flatMap(n => posLongGen.map(d => (n, d)))) {
      case (num, denom) =>
        val fraction = NonNegFraction(num, denom)
        val decimal = fraction.toBigDecimal
        val expected = BigDecimal(num.value) / BigDecimal(denom.value)

        expect(decimal == expected)
    }
  }
}
