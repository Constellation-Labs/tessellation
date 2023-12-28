package org.tessellation.node.shared.domain.seedlist

import cats.syntax.all._

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.FunSuite
import weaver.scalacheck.Checkers

object RangeSuite extends FunSuite with Checkers {

  implicit class DecodeOps(input: String) {
    def asRange: Option[Range] = Range.decode(input).toOption
  }

  test("decodes terminated range") {
    val actual = "10-15".asRange
    val expected = Range(10L, 15L).some

    expect.eql(expected, actual)
  }

  test("ignores leading and trailing spaces") {
    val actual = "  20  -  30  ".asRange
    val expected = Range(20L, 30L).some

    expect.eql(expected, actual)
  }

  test("decodes unterminated range and assume the end is NonNegLong.MaxValue") {
    val actual = "11".asRange
    val expected = Range(11L, NonNegLong.MaxValue).some

    expect.eql(expected, actual)
  }

  test("decodes unterminated range with delimiter") {
    val actual = "12-".asRange
    val expected = Range(12L, NonNegLong.MaxValue).some

    expect.eql(expected, actual)
  }

  test("decodes descending range") {
    val actual = "99-60".asRange
    val expected = Range(99L, 60L).some

    expect.eql(expected, actual)
  }

  test("returns InvalidOrdinal for non-long entry") {
    val actual = Range.decode("a-6")
    val expected = InvalidOrdinal("a").invalidNel

    expect.eql(expected, actual)
  }

  test("returns InvalidRangeFormat for negative long entry") {
    val actual = Range.decode("-14-25")
    val expected = InvalidRangeFormat(List("", "14", "25")).invalidNel

    expect.eql(expected, actual)
  }

  test("returns InvalidRangeFormat for more than 2 non-negative long entries") {
    val actual = Range.decode("20-34-46")
    val expected = InvalidRangeFormat(List("20", "34", "46")).invalidNel

    expect.eql(expected, actual)
  }

}
