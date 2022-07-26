package org.tessellation.infrastructure.rewards

import cats.syntax.all._

import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}

object Arithmetic {

  implicit class NonNegLongOps(a: NonNegLong) {

    import scala.math.Integral.Implicits._

    def +(b: NonNegLong): Either[ArithmeticException, NonNegLong] =
      Either.catchOnly[ArithmeticException](NonNegLong.unsafeFrom(Math.addExact(a, b)))

    def -(b: NonNegLong): Either[ArithmeticException, NonNegLong] =
      Either.catchOnly[ArithmeticException](NonNegLong.unsafeFrom(Math.subtractExact(a, b)))

    def *(b: NonNegLong): Either[ArithmeticException, NonNegLong] =
      Either.catchOnly[ArithmeticException](NonNegLong.unsafeFrom(Math.multiplyExact(a, b)))

    def /(b: PosLong): NonNegLong =
      NonNegLong.unsafeFrom(a.value / b.value)

    def /(b: NonNegLong): Either[ArithmeticException, NonNegLong] =
      Either.cond(b =!= 0L, NonNegLong.unsafeFrom(a.value / b.value), new ArithmeticException("division by zero"))

    def /%(b: PosLong): (NonNegLong, NonNegLong) =
      (a.value /% b.value).bimap(NonNegLong.unsafeFrom, NonNegLong.unsafeFrom)

    def /%(b: NonNegLong): Either[ArithmeticException, (NonNegLong, NonNegLong)] =
      Either.cond(
        b =!= 0L,
        (a.value /% b.value).bimap(NonNegLong.unsafeFrom, NonNegLong.unsafeFrom),
        new ArithmeticException("division by zero")
      )
  }

}
