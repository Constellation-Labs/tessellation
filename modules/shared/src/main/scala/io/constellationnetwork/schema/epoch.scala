package io.constellationnetwork.schema

import cats.Order._
import cats.implicits.catsSyntaxEitherId
import cats.kernel.{Next, Order, PartialOrder}
import cats.syntax.semigroup._

import scala.util.control.NoStackTrace

import io.constellationnetwork.ext.derevo.ordering
import io.constellationnetwork.schema.NonNegLongOps._
import io.constellationnetwork.schema.epoch.EpochProgress.{EpochOverflow, EpochUnderflow}

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong
import io.estatico.newtype.macros.newtype

object epoch {

  @derive(eqv, encoder, decoder, show, order, ordering, derevo.cats.semigroup)
  @newtype
  case class EpochProgress(value: NonNegLong) {
    def plus(that: EpochProgress): Either[EpochOverflow.type, EpochProgress] =
      value.plus(that.value) match {
        case Left(_)      => EpochOverflow.asLeft
        case Right(value) => EpochProgress(value).asRight
      }

    def minus(that: EpochProgress): Either[EpochUnderflow.type, EpochProgress] =
      value.minus(that.value) match {
        case Left(_)      => EpochUnderflow.asLeft
        case Right(value) => EpochProgress(value).asRight
      }
  }

  object EpochProgress {
    val MinValue: EpochProgress = EpochProgress(NonNegLong.MinValue)
    val MaxValue: EpochProgress = EpochProgress(NonNegLong.MaxValue)

    implicit val next: Next[EpochProgress] = new Next[EpochProgress] {
      def next(a: EpochProgress): EpochProgress = EpochProgress(a.value |+| NonNegLong(1L))
      def partialOrder: PartialOrder[EpochProgress] = Order[EpochProgress]
    }

    @derive(eqv, show, encoder, decoder)
    sealed trait EpochArithmeticError extends NoStackTrace
    case object EpochOverflow extends EpochArithmeticError
    case object EpochUnderflow extends EpochArithmeticError
  }

}
