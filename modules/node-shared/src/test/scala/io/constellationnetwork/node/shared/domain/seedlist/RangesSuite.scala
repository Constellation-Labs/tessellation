package io.constellationnetwork.node.shared.domain.seedlist

import cats.data.NonEmptyList
import cats.syntax.all._

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.FunSuite
import weaver.scalacheck.Checkers

object RangesSuite extends FunSuite with Checkers {

  implicit class DecodeOps(input: String) {
    def asRanges: Option[NonEmptyList[Range]] = ranges.decode(input).toOption
  }

  implicit class ExpectedOps(ranges: List[(Long, Long)]) {
    def asExpected: Option[NonEmptyList[Range]] = NonEmptyList
      .fromListUnsafe(ranges)
      .map {
        case (start, end) =>
          Range(
            NonNegLong.unsafeFrom(start),
            NonNegLong.unsafeFrom(end)
          )
      }
      .some
  }

  test("decodes multiple ranges") {
    val actual = "10-15;30-60".asRanges
    val expected = List((10L, 15L), (30L, 60L)).asExpected

    expect.eql(expected, actual)
  }

  test("decodes ranges with trailing delimiter") {
    val actual = "99-100;150-300".asRanges
    val expected = List((99L, 100L), (150L, 300L)).asExpected

    expect.eql(expected, actual)
  }

  test("merges overlapping ranges") {
    val actual = "0-20;20-50;60-69;62-65;70-79".asRanges
    val expected = List((0L, 50L), (60L, 79L)).asExpected

    expect.eql(expected, actual)
  }

  test("merges adjacent ranges inclusively") {
    val actual = "0-19;20-34;35-60".asRanges
    val expected = List((0L, 60L)).asExpected

    expect.eql(expected, actual)
  }

  test("returns ReversedRange when there are descending ranges") {
    val actual = ranges.decode("30-60;70-40;80-100")
    val expected = ReversedRange(70L, 40L).invalidNel

    expect.eql(expected, actual)
  }

  test("returns InvalidInput when the input is empty") {
    val actual = ranges.decode("")
    val expected = InvalidInput("").invalidNel

    expect.eql(expected, actual)
  }

}
