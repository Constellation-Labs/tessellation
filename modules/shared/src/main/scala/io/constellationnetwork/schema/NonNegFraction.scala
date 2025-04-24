package io.constellationnetwork.schema

import java.math.MathContext

import cats.syntax.all._
import cats.{ApplicativeThrow, MonadThrow}

import scala.math.BigInt

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.{NonNegative, Positive}
import eu.timepit.refined.string.Regex
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import eu.timepit.refined.{refineMV, refineV}
import pureconfig.ConfigReader
import pureconfig.error.{CannotConvert, ConfigReaderFailure, FailureReason}

/** Represents a fractional value with deterministic behavior across machines. Internally stored as non-negative Longs to ensure
  * deterministic calculations.
  */
@derive(encoder, decoder, order, show)
case class NonNegFraction(numerator: Long Refined NonNegative, denominator: Long Refined Positive) {
  def toBigDecimal: BigDecimal = BigDecimal(numerator.value) / BigDecimal(denominator.value)

  /** Adds two NonNegFractions with overflow protection */
  def +[F[_]: MonadThrow](other: NonNegFraction): F[NonNegFraction] =
    if (numerator.value == 0) {
      other.pure[F].widen[NonNegFraction]
    } else if (other.numerator.value == 0) {
      this.pure[F].widen[NonNegFraction]
    } else {
      // Convert to BigInt for calculation
      val n1 = BigInt(numerator.value)
      val d1 = BigInt(denominator.value)
      val n2 = BigInt(other.numerator.value)
      val d2 = BigInt(other.denominator.value)

      // Find common denominator
      val gcd = d1.gcd(d2)
      val lcm = (d1 / gcd) * d2

      // Scale numerators to the common denominator
      val scaledNum1 = n1 * (lcm / d1)
      val scaledNum2 = n2 * (lcm / d2)

      // Add the numerators
      val resultNum = scaledNum1 + scaledNum2
      val resultDenom = lcm

      // Reduce the fraction
      val gcdResult = resultNum.gcd(resultDenom)
      val reducedNum = resultNum / gcdResult
      val reducedDenom = resultDenom / gcdResult

      // Check for overflow and approximate if needed
      NonNegFraction.approximateToLongRange[F](reducedNum, reducedDenom)
    }

  /** Subtracts another NonNegFraction from this one with underflow protection */
  def -[F[_]: MonadThrow](other: NonNegFraction): F[NonNegFraction] =
    if (other.numerator.value == 0) {
      this.pure[F].widen[NonNegFraction]
    } else if (this == other) {
      NonNegFraction.zero.pure[F].widen[NonNegFraction]
    } else {
      // Convert to BigInt for calculation
      val n1 = BigInt(numerator.value)
      val d1 = BigInt(denominator.value)
      val n2 = BigInt(other.numerator.value)
      val d2 = BigInt(other.denominator.value)

      // Find common denominator
      val gcd = d1.gcd(d2)
      val lcm = (d1 / gcd) * d2

      // Scale numerators to the common denominator
      val scaledNum1 = n1 * (lcm / d1)
      val scaledNum2 = n2 * (lcm / d2)

      // Check if result would be negative
      if (scaledNum1 < scaledNum2) {
        new ArithmeticException("Result would be negative").raiseError[F, NonNegFraction]
      } else {
        // Subtract the numerators
        val resultNum = scaledNum1 - scaledNum2
        val resultDenom = lcm

        // Reduce the fraction
        val gcdResult = resultNum.gcd(resultDenom)
        val reducedNum = resultNum / gcdResult
        val reducedDenom = resultDenom / gcdResult

        // Check for overflow and approximate if needed
        NonNegFraction.approximateToLongRange[F](reducedNum, reducedDenom)
      }
    }
}

object NonNegFraction {
  // Approximates a BigInt fraction to fit within Long bounds while preserving the value as closely as possible
  private def approximateToLongRange[F[_]: MonadThrow](numerator: BigInt, denominator: BigInt): F[NonNegFraction] =
    // If the result fits in Long range, use it exactly
    if (numerator <= Long.MaxValue && denominator <= Long.MaxValue) {
      for {
        refinedNum <- refineV[NonNegative](numerator.toLong).leftMap(new IllegalArgumentException(_)).liftTo[F]
        refinedDenom <- refineV[Positive](denominator.toLong).leftMap(new IllegalArgumentException(_)).liftTo[F]
      } yield NonNegFraction(refinedNum, refinedDenom)
    } else {
      // We need to approximate
      val approximated = approximateOverflowingFraction(numerator, denominator)
      for {
        refinedNum <- refineV[NonNegative](approximated._1).leftMap(new IllegalArgumentException(_)).liftTo[F]
        refinedDenom <- refineV[Positive](approximated._2).leftMap(new IllegalArgumentException(_)).liftTo[F]
      } yield NonNegFraction(refinedNum, refinedDenom)
    }

  // Helper function to approximate a BigInt fraction that doesn't fit in Long
  private def approximateOverflowingFraction(numerator: BigInt, denominator: BigInt): (Long, Long) =
    // If zero, return the canonical form
    if (numerator.signum == 0) {
      (0L, 1L)
    } else {
      // Get a decimal approximation for comparison
      val decimalValue = numerator.toDouble / denominator.toDouble

      // For very large numbers, use the biggest possible representation
      if (decimalValue > Long.MaxValue) {
        (Long.MaxValue, 1L)
      } else if (decimalValue > 0 && decimalValue < 1.0 / Long.MaxValue) {
        // For very small numbers, use the smallest possible representation
        (1L, Long.MaxValue)
      } else if (decimalValue >= 1.0) {
        // Values >= 1: try to represent as N/1
        val approxNum = Math.min(Long.MaxValue, Math.round(decimalValue))
        (approxNum, 1L)
      } else {
        // Values < 1: try to represent as 1/D
        val denom = Math.min(Long.MaxValue, Math.round(1.0 / decimalValue))
        // Safety check - use Max safe value if calculation resulted in invalid denominator
        if (denom <= 0) (1L, Long.MaxValue) else (1L, denom)
      }
    }

  def fromDouble[F[_]: MonadThrow](value: Double): F[NonNegFraction] =
    if (value < 0) {
      new IllegalArgumentException("Value must be non-negative").raiseError[F, NonNegFraction]
    } else {
      val scale = 8 // tessellation specific precision
      val denominator = BigInt(10).pow(scale).toLong
      val numerator = (BigDecimal(value) * BigDecimal(denominator)).toLong
      // ^^ This will round down by truncating any digits beyond 8 places ^^

      for {
        refinedNum <- refineV[NonNegative](numerator).leftMap(new IllegalArgumentException(_)).liftTo[F]
        refinedDenom <- refineV[Positive](denominator).leftMap(new IllegalArgumentException(_)).liftTo[F]
      } yield NonNegFraction(refinedNum, refinedDenom)
    }

  def fromPercentage[F[_]: MonadThrow](percentage: Double): F[NonNegFraction] =
    if (percentage < 0) {
      new IllegalArgumentException("Percentage must be non-negative").raiseError[F, NonNegFraction]
    } else {
      fromDouble[F](percentage / 100.0)
    }

  def apply[F[_]: MonadThrow](numerator: Long, denominator: Long): F[NonNegFraction] =
    if (numerator < 0) {
      new IllegalArgumentException("Numerator must be non-negative").raiseError[F, NonNegFraction]
    } else if (denominator <= 0) {
      new IllegalArgumentException("Denominator must be positive").raiseError[F, NonNegFraction]
    } else {
      for {
        refinedNum <- refineV[NonNegative](numerator).leftMap(new IllegalArgumentException(_)).liftTo[F]
        refinedDenom <- refineV[Positive](denominator).leftMap(new IllegalArgumentException(_)).liftTo[F]
      } yield NonNegFraction(refinedNum, refinedDenom)
    }

  // Factory methods for common values
  val one: NonNegFraction = NonNegFraction(
    refineMV[NonNegative](1L),
    refineMV[Positive](1L)
  )

  val zero: NonNegFraction = NonNegFraction(
    refineMV[NonNegative](0L),
    refineMV[Positive](1L)
  )

  // Unsafe constructor - use with caution, prefers to be explicit about potential failure
  def unsafeFrom(numerator: Long, denominator: Long): NonNegFraction =
    NonNegFraction(NonNegLong.unsafeFrom(numerator), PosLong.unsafeFrom(denominator))

  private def parseFractionString[F[_]: MonadThrow](str: String): F[(Long, Long)] = {
    val FractionPattern = """^\s*(\d+)\s*/\s*(\d+)\s*$""".r

    str match {
      case FractionPattern(numStr, denomStr) =>
        val numeratorF = MonadThrow[F]
          .catchNonFatal(numStr.toLong)
          .adaptErr(_ => new IllegalArgumentException(s"Cannot parse numerator '$numStr' as Long"))

        val denominatorF = MonadThrow[F]
          .catchNonFatal(denomStr.toLong)
          .adaptErr(_ => new IllegalArgumentException(s"Cannot parse denominator '$denomStr' as Long"))

        (numeratorF, denominatorF).mapN((num, denom) => (num, denom))

      case _ =>
        MonadThrow[F].raiseError(
          new IllegalArgumentException(s"Invalid fraction format. Expected 'numerator/denominator', got '$str'")
        )
    }
  }

  // Config reader for parsing fractions from configuration
  implicit def nonNegFractionConfigReader[F[_]: MonadThrow]: ConfigReader[F[NonNegFraction]] =
    ConfigReader.fromString[F[NonNegFraction]] { str =>
      Either.catchNonFatal {
        parseFractionString[F](str).flatMap {
          case (numerator, denominator) =>
            apply[F](numerator, denominator)
        }
      }.leftMap(ex => CannotConvert(str, "NonNegFraction", ex.getMessage))
    }
}
