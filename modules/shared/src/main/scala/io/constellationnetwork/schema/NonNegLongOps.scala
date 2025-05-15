package io.constellationnetwork.schema

import cats.implicits.{catsSyntaxEitherId, catsSyntaxSemigroup, toBifunctorOps}

import scala.util.control.NoStackTrace

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.types.all.NonNegLong

object NonNegLongOps {
  @derive(eqv, encoder, decoder, show)
  sealed trait NonNegLongArithmeticError extends NoStackTrace
  case object AmountOverflow extends NonNegLongArithmeticError
  case object AmountUnderflow extends NonNegLongArithmeticError

  implicit class NonNegLongOper(value: NonNegLong) {
    def plus(that: NonNegLong): Either[AmountOverflow.type, NonNegLong] = {
      val sum = value.value |+| that.value

      if (sum >= value.value && sum >= that.value) {
        NonNegLong.unsafeFrom(sum).asRight
      } else AmountOverflow.asLeft[NonNegLong]
    }

    def minus(that: NonNegLong): Either[AmountUnderflow.type, NonNegLong] =
      NonNegLong
        .from(value.value - that.value)
        .bimap(_ => AmountUnderflow, identity)
  }
}
