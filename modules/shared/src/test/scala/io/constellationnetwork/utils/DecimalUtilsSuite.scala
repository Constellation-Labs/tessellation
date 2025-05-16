package io.constellationnetwork.utils

import cats.effect.IO
import cats.syntax.all._

import scala.math.BigDecimal.RoundingMode

import org.scalacheck.Gen
import weaver.scalacheck.Checkers
import weaver.{Expectations, SimpleIOSuite}

object DecimalUtilsSuite extends SimpleIOSuite with Checkers {

  // Generators for test data
  val nonNegativeDecimalGen: Gen[BigDecimal] = Gen.choose(0.0, 1000000.0).map(BigDecimal(_))
  val zeroDecimalGen: Gen[BigDecimal] = Gen.const(BigDecimal(0))
  val positiveDecimalGen: Gen[BigDecimal] = Gen.choose(0.000001, 1000000.0).map(BigDecimal(_))
  val negativeDecimalGen: Gen[BigDecimal] = Gen.choose(-1000000.0, -0.000001).map(BigDecimal(_))
  val anyDecimalGen: Gen[BigDecimal] = Gen.choose(-1000000.0, 1000000.0).map(BigDecimal(_))
  val smallDecimalGen: Gen[BigDecimal] = Gen.choose(0.0000001, 0.01).map(BigDecimal(_))
  val largeDecimalGen: Gen[BigDecimal] = Gen.choose(1000000.0, 10000000.0).map(BigDecimal(_))
  val scaleGen: Gen[Int] = Gen.choose(0, 10)
  val nonZeroDecimalGen: Gen[BigDecimal] = anyDecimalGen.suchThat(_ != 0)
  val datumLongGen: Gen[Long] = Gen.choose(1L, Long.MaxValue / 100) // Avoid overflow

  def testWithSample[A](genSample: Option[A])(testFn: A => Expectations): IO[Expectations] =
    genSample.fold(IO.pure(failure("Failed to generate test data")))(a => IO.pure(testFn(a)))

  def expectGen[A](gen: Gen[A])(f: A => Expectations): IO[Expectations] =
    gen.sample.traverse(a => IO.pure(f(a))).map(_.getOrElse(failure("Failed to generate test data")))

  test("toDatumFormat should convert zero correctly") {
    expect.eql(0L, DecimalUtils.toDatumFormat(BigDecimal(0))).pure[IO]
  }

  test("toDatumFormat should convert positive BigDecimal to datum format") {
    expectGen(positiveDecimalGen) { value =>
      val result = DecimalUtils.toDatumFormat(value)
      val expected = (value * DecimalUtils.DATUM_USD).setScale(0, RoundingMode.HALF_UP).longValue
      expect.eql(expected, result)
    }
  }

  test("toDatumFormat should convert negative BigDecimal to datum format") {
    expectGen(negativeDecimalGen) { value =>
      val result = DecimalUtils.toDatumFormat(value)
      val expected = (value * DecimalUtils.DATUM_USD).setScale(0, RoundingMode.HALF_UP).longValue
      expect.eql(expected, result)
    }
  }

  test("toDatumFormat should handle very small decimals") {
    expectGen(smallDecimalGen) { value =>
      val result = DecimalUtils.toDatumFormat(value)
      val expected = (value * DecimalUtils.DATUM_USD).setScale(0, RoundingMode.HALF_UP).longValue
      expect.eql(expected, result)
    }
  }

  test("toDatumFormat should handle large values safely") {
    val safeMax = (Long.MaxValue / DecimalUtils.DATUM_USD.toDouble) * 0.9
    val safeGen = Gen.choose(1.0, safeMax).map(BigDecimal(_))

    expectGen(safeGen) { value =>
      val result = DecimalUtils.toDatumFormat(value)
      val expected = (value * DecimalUtils.DATUM_USD).setScale(0, RoundingMode.HALF_UP).longValue
      expect.eql(expected, result)
    }
  }

  test("fromDatumFormat should convert zero correctly") {
    expect.eql(BigDecimal(0), DecimalUtils.fromDatumFormat(0L)).pure[IO]
  }

  test("fromDatumFormat should convert positive datum format to BigDecimal") {
    expectGen(Gen.posNum[Long]) { value =>
      val result = DecimalUtils.fromDatumFormat(value)
      val expected = BigDecimal(value) / DecimalUtils.DATUM_USD
      expect.eql(expected, result)
    }
  }

  test("fromDatumFormat should convert negative datum format to BigDecimal") {
    expectGen(Gen.negNum[Long]) { value =>
      val result = DecimalUtils.fromDatumFormat(value)
      val expected = BigDecimal(value) / DecimalUtils.DATUM_USD
      expect.eql(expected, result)
    }
  }

  test("toDatumFormat and fromDatumFormat should be inverse operations for positive values") {
    expectGen(positiveDecimalGen) { value =>
      // Use precision that's less than the maximum precision of DATUM_USD
      val scaledValue = value.setScale(6, RoundingMode.HALF_UP)
      val datum = DecimalUtils.toDatumFormat(scaledValue)
      val roundTrip = DecimalUtils.fromDatumFormat(datum)

      // Due to potential rounding during conversion, we need to check within a small epsilon
      // The epsilon is relative to the scale of DATUM_USD (8 decimal places)
      val epsilon = BigDecimal(1) / (DecimalUtils.DATUM_USD / 2)
      expect((scaledValue - roundTrip).abs <= epsilon)
    }
  }

  test("toDatumFormat and fromDatumFormat should be inverse operations for negative values") {
    expectGen(negativeDecimalGen) { value =>
      val scaledValue = value.setScale(6, RoundingMode.HALF_UP)
      val datum = DecimalUtils.toDatumFormat(scaledValue)
      val roundTrip = DecimalUtils.fromDatumFormat(datum)
      val epsilon = BigDecimal(1) / (DecimalUtils.DATUM_USD / 2)
      expect((scaledValue - roundTrip).abs <= epsilon)
    }
  }

  test("toDatumFormat should handle the maximum representable value without overflow") {
    val maxSafeValue = BigDecimal(Long.MaxValue) / DecimalUtils.DATUM_USD
    // Use a value slightly less than max to avoid overflow
    val testValue = maxSafeValue * 0.9

    // This should not throw an exception
    expect(DecimalUtils.toDatumFormat(testValue) > 0).pure[IO]
  }

  test("toDatumFormat should handle maximum precision") {
    // Test with a value that uses all 8 decimal places
    val value = BigDecimal("0.00000001") // 8 decimal places
    val result = DecimalUtils.toDatumFormat(value)
    IO.pure(expect.eql(1L, result)) // Should convert to 1 datum
  }

  test("fromDatumFormat should preserve precision") {
    val datumValue = 1L
    val result = DecimalUtils.fromDatumFormat(datumValue)
    val expected = BigDecimal("0.00000001") // 8 decimal places
    IO.pure(expect.eql(expected, result))
  }

  test("safeDivide should perform normal division when denominator is not zero") {
    val gen = for {
      numerator <- anyDecimalGen
      denominator <- nonZeroDecimalGen
    } yield (numerator, denominator)

    expectGen(gen) { case (numerator, denominator) =>
      val result = DecimalUtils.safeDivide(numerator, denominator)
      val expected = numerator / denominator
      expect.eql(expected, result)
    }
  }

  test("round should round to the specified scale") {
    val gen = for {
      value <- anyDecimalGen
      scale <- scaleGen
    } yield (value, scale)

    expectGen(gen) { case (value, scale) =>
      val result = DecimalUtils.round(value, scale)
      val expected = value.setScale(scale, RoundingMode.HALF_UP)
      expect.eql(expected, result)
    }
  }
}