package io.constellationnetwork.node.shared.domain.seedlist

import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.syntax.all._

import scala.util.control.NoStackTrace

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong

@derive(eqv, show)
sealed trait RangesError extends NoStackTrace

case class InvalidInput(str: String) extends RangesError {
  override def getMessage = s"Invalid input: $str"
}

case class InvalidRange(error: RangeError) extends RangesError {
  override def getMessage: String = error.getMessage
}

case class ReversedRange(start: NonNegLong, end: NonNegLong) extends RangesError {
  override def getMessage = s"The start, $start, cannot be greater than the end, $end"
}

object ranges {

  type RangesValidationResult[A] = ValidatedNel[RangesError, A]

  private val delimiter = ";"

  def decode(input: String): RangesValidationResult[NonEmptyList[Range]] =
    Validated
      .condNel(
        input.nonEmpty,
        input,
        InvalidInput(input)
      )
      .andThen {
        _.split(delimiter).toList.traverse { r =>
          Range
            .decode(r)
            .leftMap(_.map(InvalidRange))
        }.andThen {
          validate(_).map(NonEmptyList.fromListUnsafe)
        }
          .map(mergeOverlaps)
      }

  private def mergeOverlaps(ranges: NonEmptyList[Range]): NonEmptyList[Range] = {
    val merged = ranges
      .sortBy(_.start.value)
      .foldLeft(List.empty[Range]) {
        case (prev :: tail, curr) if curr.start - prev.end <= 1 =>
          val merged = Range(
            prev.start,
            NonNegLong.unsafeFrom(
              Math.max(prev.end, curr.end)
            )
          )

          merged :: tail
        case (acc, curr) =>
          curr :: acc
      }
      .reverse

    NonEmptyList.fromListUnsafe(merged)
  }

  private def validate(ranges: List[Range]): RangesValidationResult[List[Range]] =
    ranges.traverse { r =>
      Validated.condNel(
        r.start <= r.end,
        r,
        ReversedRange(r.start, r.end)
      )
    }

}
