package io.constellationnetwork.kernel

import cats.effect.IO
import cats.syntax.applicative._

import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object ΩListSuite extends SimpleIOSuite with Checkers {
  case object A extends Ω
  case object B extends Ω
  case object C extends Ω
  case object D extends Ω
  case object E extends Ω

  test("adding an element to an empty lists should return a list with this single element") {
    val empty = ΩNil
    val elem = A

    val expected = ::(elem, ΩNil)
    val result = elem :: empty

    expect.same(expected, result).pure[IO]
  }

  test("adding an empty list to a single element should return a list with this single element") {
    val empty = ΩNil
    val elem = A

    val expected = ::(elem, ΩNil)
    val result = empty :: elem

    expect.same(expected, result).pure[IO]
  }

  test("adding an element to a non-empty list should return a list with this single element prepended") {
    val list = ::(B, ::(C, ΩNil))
    val elem = A

    val expected = ::(elem, ::(B, ::(C, ΩNil)))
    val result = elem :: list

    expect.same(expected, result).pure[IO]
  }

  test("adding a non-empty list to a single element should return a list with this single element appended") {
    val list = ::(A, ::(B, ΩNil))
    val elem = C

    val expected = ::(A, ::(B, ::(elem, ΩNil)))
    val result = list :: elem

    expect.same(expected, result).pure[IO]
  }

  test("adding two empty lists should return an empty list") {
    val empty1 = ΩNil
    val empty2 = ΩNil

    val expected = ΩNil
    val result = empty1 :: empty2

    expect.same(expected, result).pure[IO]
  }

  test("adding a non-empty list to an empty list should return a non-empty list") {
    val list = ::(A, ::(B, ΩNil))
    val empty = ΩNil

    val expected = list
    val result = list :: empty

    expect.same(expected, result).pure[IO]
  }

  test("adding an empty list to a non-empty list should return a non-empty list") {
    val list = ::(A, ::(B, ΩNil))
    val empty = ΩNil

    val expected = list
    val result = empty :: list

    expect.same(expected, result).pure[IO]
  }

  test("adding a non-empty list to another non-empty list should prepend the first list to the second list") {
    val listAB = ::(A, ::(B, ΩNil))
    val listCDE = ::(C, ::(D, ::(E, ΩNil)))

    val expected = ::(A, ::(B, ::(C, ::(D, ::(E, ΩNil)))))
    val result = listAB :: listCDE

    expect.same(expected, result).pure[IO]
  }
}
