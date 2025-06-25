package io.constellationnetwork.domain.seedlist

import cats.data.ValidatedNel
import cats.syntax.all._

import scala.util.control.NoStackTrace

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong

@derive(eqv, show)
sealed trait RangeError extends NoStackTrace

case class InvalidOrdinal(ordinal: String) extends RangeError {
  override def getMessage = s"Invalid ordinal: $ordinal"
}

case class InvalidRangeFormat(range: List[String]) extends RangeError {
  override def getMessage = s"Invalid range format: ${range.mkString("-")}"
}

@derive(eqv, show)
case class Range(start: NonNegLong, end: NonNegLong)

object Range {

  type RangeValidationResult[A] = ValidatedNel[RangeError, A]

  private val delimiter = "-"

  def decode(input: String): RangeValidationResult[Range] = {
    val values = input.split(delimiter).map(_.trim).toList

    parseRange(values)
  }

  private def parseRange(range: List[String]): RangeValidationResult[Range] =
    range match {
      case start :: Nil =>
        parseOrdinal(start).map(Range(_, NonNegLong.MaxValue))
      case start :: end :: Nil =>
        (parseOrdinal(start), parseOrdinal(end)).mapN {
          case (start, end) =>
            Range(start = start, end = end)
        }
      case _ =>
        InvalidRangeFormat(range).invalidNel[Range]
    }

  private def parseOrdinal(ordinal: String): RangeValidationResult[NonNegLong] =
    Either
      .fromOption(ordinal.toLongOption, InvalidOrdinal(ordinal))
      .flatMap(a => NonNegLong.from(a).leftMap(_ => InvalidOrdinal(ordinal)))
      .toValidatedNel

}
