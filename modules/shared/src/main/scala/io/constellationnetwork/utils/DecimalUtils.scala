package io.constellationnetwork.utils

import scala.math.BigDecimal.RoundingMode

/**
 * Utility class for decimal operations and conversions.
 * Provides consistent handling of decimal values across the codebase.
 */
object DecimalUtils {
  /**
   * Conversion factor from USD to datum format.
   * 1 USD = 100,000,000 datums (8 decimal places)
   */
  val DATUM_USD: Long = 100000000L

  /**
   * Converts a BigDecimal value to datum format (long value).
   *
   * @param value The BigDecimal value to convert
   * @return The value in datum format (multiplied by DATUM_USD)
   */
  def toDatumFormat(value: BigDecimal): Long =
    (value * DATUM_USD).setScale(0, RoundingMode.HALF_UP).longValue

  /**
   * Converts a datum format value to BigDecimal.
   *
   * @param value The datum format value to convert
   * @return The value as BigDecimal (divided by DATUM_USD)
   */
  def fromDatumFormat(value: Long): BigDecimal =
    BigDecimal(value) / DATUM_USD

  /**
   * Performs safe division, handling division by zero gracefully.
   *
   * @param numerator The numerator
   * @param denominator The denominator
   * @param defaultValue The default value to return if denominator is zero
   * @return The result of division, or defaultValue if denominator is zero
   */
  def safeDivide(numerator: BigDecimal, denominator: BigDecimal, defaultValue: BigDecimal = BigDecimal(0)): BigDecimal =
    if (denominator == 0) defaultValue else numerator / denominator

  /**
   * Rounds a BigDecimal value to the specified number of decimal places.
   *
   * @param value The value to round
   * @param scale The number of decimal places
   * @param mode The rounding mode (defaults to HALF_UP)
   * @return The rounded value
   */
  def round(value: BigDecimal, scale: Int, mode: BigDecimal.RoundingMode.Value = RoundingMode.HALF_UP): BigDecimal =
    value.setScale(scale, mode)

  /**
   * Syntax extensions for BigDecimal to provide more functional operations.
   */
  object syntax {
    /**
     * Extension methods for BigDecimal.
     */
    implicit class BigDecimalOps(val value: BigDecimal) extends AnyVal {
      /**
       * Converts this BigDecimal to datum format.
       *
       * @return The value in datum format
       */
      def toDatumFormat: Long = DecimalUtils.toDatumFormat(value)

      /**
       * Safely divides this BigDecimal by another.
       *
       * @param divisor The divisor
       * @param defaultValue The default value to return if divisor is zero
       * @return The result of division, or defaultValue if divisor is zero
       */
      def safeDivideBy(divisor: BigDecimal, defaultValue: BigDecimal = BigDecimal(0)): BigDecimal =
        DecimalUtils.safeDivide(value, divisor, defaultValue)

      /**
       * Rounds this BigDecimal to the specified number of decimal places.
       *
       * @param scale The number of decimal places
       * @param mode The rounding mode (defaults to HALF_UP)
       * @return The rounded value
       */
      def roundedHalfUp(scale: Int, mode: BigDecimal.RoundingMode.Value = RoundingMode.HALF_UP): BigDecimal =
        DecimalUtils.round(value, scale, mode)
    }

    /**
     * Extension methods for Long to convert from datum format.
     */
    implicit class LongOps(val value: Long) extends AnyVal {
      /**
       * Converts this Long from datum format to BigDecimal.
       *
       * @return The value as BigDecimal
       */
      def fromDatumFormat: BigDecimal = DecimalUtils.fromDatumFormat(value)
    }
  }
}
