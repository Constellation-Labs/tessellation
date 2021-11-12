package org.tessellation.kernel

import cats.syntax.either._
import cats.syntax.parallel._
import cats.syntax.semigroup._
import cats.{Id, Monoid}

import org.tessellation.kernel.Cell.NullTerminal

import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}
import weaver.FunSuite

object CellMonoidManualTestSuite extends FunSuite {
  import TestCells._

  implicit val M: Monoid[Cell[Id, StackF, Ω, Either[CellError, Ω], Ω]] = Cell.cellMonoid[Id, StackF]

  test("creates empty") {
    val empty = M.empty

    expect.same(NullTerminal, empty.data)
  }

  test("empty hylo is just an identity") {
    val empty = M.empty
    val hylo = empty.run()
    val input = empty.data

    expect.same(Right(input), hylo)
  }

  test("combine is an identity function (right empty)") {
    val testCell = LongToStringCell(TestLongInput(10L))
    val emptyCell = M.empty
    val combined = M.combine(
      testCell,
      emptyCell
    )

    expect.same(testCell, combined)
  }

  test("combine is an identity function (left empty)") {
    val testCell = LongToStringCell(TestLongInput(10L))
    val emptyCell = M.empty
    val combined = M.combine(
      emptyCell,
      testCell
    )

    expect.same(testCell, combined)
  }

  test("combine same types") {
    val testCellA = LongToStringCell(TestLongInput(1L))
    val testCellB = LongToStringCell(TestLongInput(2L))

    val expected = (testCellA.run(), testCellB.run()).parMapN {
      case (resultA, resultB) =>
        resultA.flatMap(a => resultB.map(b => a :: b))
    }

    val combined = M.combine(
      testCellA,
      testCellB
    )

    expect.same(expected, combined.run())
  }

  test("combine different types") {
    val intToStringCell: Cell[Id, StackF, Ω, Either[CellError, Ω], Ω] = LongToStringCell(TestLongInput(1L))
    val stringToIntCell: Cell[Id, StackF, Ω, Either[CellError, Ω], Ω] = StringToLongCell(TestStringInput("2"))

    val expected = (intToStringCell.run(), stringToIntCell.run()).parMapN {
      case (intToStringResult, stringToIntResult) =>
        intToStringResult.flatMap(a => stringToIntResult.map(b => a :: b))
    }

    val combined = intToStringCell |+| stringToIntCell

    expect.same(expected, combined.run())
  }

  object TestCells {
    case class InputLongF(input: Long) extends Ω
    case class TestLongInput(value: Long) extends Ω
    case class TestStringOutput(value: String) extends Ω

    class LongToStringCell(input: TestLongInput)
        extends Cell[Id, StackF, Ω, Either[CellError, Ω], Ω](
          input,
          scheme.hyloM(
            AlgebraM[Id, StackF, Either[CellError, Ω]] {
              case More(a)      => a
              case Done(result) => result
            },
            CoalgebraM[Id, StackF, Ω] {
              case InputLongF(input) =>
                Done(TestStringOutput(input.toString).asRight[CellError])
              case _ =>
                Done(CellError("Unhandled coalgebra case").asLeft[Ω])
            }
          ),
          _ => InputLongF(input.value)
        ) {}

    object LongToStringCell {
      def apply(input: TestLongInput): LongToStringCell = new LongToStringCell(input)
    }

    case class InputStringF(input: String) extends Ω
    case class TestStringInput(value: String) extends Ω
    case class TestLongOutput(value: Long) extends Ω

    class StringToLongCell(input: TestStringInput)
        extends Cell[Id, StackF, Ω, Either[CellError, Ω], Ω](
          input,
          scheme.hyloM(
            AlgebraM[Id, StackF, Either[CellError, Ω]] {
              case More(a)      => a
              case Done(result) => result
            },
            CoalgebraM[Id, StackF, Ω] {
              case InputStringF(input) =>
                Done(TestLongOutput(input.toLong).asRight[CellError])
              case _ =>
                Done(CellError("Unhandled coalgebra case").asLeft[Ω])
            }
          ),
          _ => InputStringF(input.value)
        ) {}
  }

  object StringToLongCell {
    def apply(input: TestStringInput): StringToLongCell = new StringToLongCell(input)
  }
}
