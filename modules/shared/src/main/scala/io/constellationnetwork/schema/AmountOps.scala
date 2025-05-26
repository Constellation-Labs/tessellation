package io.constellationnetwork.schema

import cats.syntax.all._
import cats.{Applicative, MonadThrow}

import scala.math.BigDecimal.RoundingMode

import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.transaction.TransactionAmount
import io.constellationnetwork.utils.DecimalUtils

import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}

/** Extension methods for Amount to provide more functional operations.
  */
object AmountOps {

  /** Extension methods for Amount.
    */
  implicit class AmountOps(val amount: Amount) {

    /** Safely divides this amount by another amount.
      *
      * @param divisor
      *   The amount to divide by
      * @param defaultValue
      *   The default value to return if divisor is zero
      * @return
      *   The result of division, or defaultValue if divisor is zero
      */
    def safeDivideBy(divisor: Amount, defaultValue: BigDecimal = BigDecimal(0)): BigDecimal =
      if (divisor.value.value == 0) defaultValue
      else BigDecimal(amount.value.value) / BigDecimal(divisor.value.value)

    /** Converts this amount to a BigDecimal.
      *
      * @return
      *   The amount as a BigDecimal
      */
    def toBigDecimal: BigDecimal = BigDecimal(amount.value.value)

    /** Multiplies this amount by a scalar value.
      *
      * @param scalar
      *   The scalar value to multiply by
      * @return
      *   The product as a BigDecimal
      */
    def times(scalar: BigDecimal): BigDecimal = toBigDecimal * scalar

    /** Rounds this amount to the specified number of decimal places.
      *
      * @param scale
      *   The number of decimal places
      * @param mode
      *   The rounding mode (defaults to HALF_UP)
      * @return
      *   The rounded value as a BigDecimal
      */
    def roundedHalfUp(scale: Int, mode: BigDecimal.RoundingMode.Value = RoundingMode.HALF_UP): BigDecimal =
      toBigDecimal.setScale(scale, mode)
  }

  /** Extension methods for BigDecimal to convert to Amount and provide additional utility methods.
    */
  implicit class BigDecimalOps(val value: BigDecimal) extends AnyVal {

    /** Converts this BigDecimal to an Amount.
      *
      * @param F
      *   The monad with error handling
      * @return
      *   The value as an Amount wrapped in F
      */
    def toAmount[F[_]: MonadThrow]: F[Amount] =
      if (value <= 0) Amount.empty.pure[F]
      else
        MonadThrow[F].fromEither(
          NonNegLong
            .from(value.setScale(0, RoundingMode.HALF_UP).longValue)
            .bimap(
              err => new IllegalArgumentException(s"Failed to create non-negative amount: $err"),
              nonNegLong => Amount(nonNegLong)
            )
        )

    /** Converts this BigDecimal to a TransactionAmount.
      *
      * @param F
      *   The monad with error handling
      * @return
      *   The value as a TransactionAmount wrapped in F
      */
    def toTransactionAmount[F[_]: MonadThrow]: F[TransactionAmount] =
      if (value <= 0) MonadThrow[F].raiseError(new IllegalArgumentException("Transaction amount must be positive"))
      else
        MonadThrow[F].fromEither(
          PosLong
            .from(value.setScale(0, RoundingMode.HALF_UP).longValue)
            .bimap(
              err => new IllegalArgumentException(s"Failed to create positive transaction amount: $err"),
              posLong => TransactionAmount(posLong)
            )
        )

    /** Converts this BigDecimal to datum format.
      *
      * @return
      *   The value in datum format
      */
    def toDatumFormat: Long = DecimalUtils.toDatumFormat(value)

    /** Safely divides this BigDecimal by another.
      *
      * @param divisor
      *   The divisor
      * @param defaultValue
      *   The default value to return if divisor is zero
      * @return
      *   The result of division, or defaultValue if divisor is zero
      */
    def safeDivideBy(divisor: BigDecimal, defaultValue: BigDecimal = BigDecimal(0)): BigDecimal =
      DecimalUtils.safeDivide(value, divisor, defaultValue)

    /** Rounds this BigDecimal to the specified number of decimal places.
      *
      * @param scale
      *   The number of decimal places
      * @param mode
      *   The rounding mode (defaults to HALF_UP)
      * @return
      *   The rounded value
      */
    def roundedHalfUp(scale: Int, mode: BigDecimal.RoundingMode.Value = RoundingMode.HALF_UP): BigDecimal =
      DecimalUtils.round(value, scale, mode)
  }
}
