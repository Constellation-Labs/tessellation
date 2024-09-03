package io.constellationnetwork.kernel

import cats.Id
import cats.kernel.Eq
import cats.kernel.laws.discipline.MonoidTests

import io.constellationnetwork.kernel.CellMonoidManualTestSuite.TestCells.{LongToStringCell, TestLongInput}

import org.scalacheck.{Arbitrary, Gen}
import weaver.FunSuite
import weaver.discipline.Discipline

object CellMonoidLawsSuite extends FunSuite with Discipline {
  // WARN: By comparing cell's run method result we can consider monoid for cells fulfilling the monoid laws.
  //       Because two functions with the same implementation are never equal to each other we can't compare
  //       the cells to each other by comparing the cell objects (neither convert nor hylo functions will be ever equal).
  implicit val eq: Eq[Cell[Id, StackF, Ω, Either[CellError, Ω], Ω]] =
    (x: Cell[Id, StackF, Ω, Either[CellError, Ω], Ω], y: Cell[Id, StackF, Ω, Either[CellError, Ω], Ω]) => x.run() == y.run()

  implicit val arbitraryCell: Arbitrary[Cell[Id, StackF, Ω, Either[CellError, Ω], Ω]] = Arbitrary(
    Gen.long.map(l => LongToStringCell(TestLongInput(l)))
  )

  checkAll("CellMonoidLaw", MonoidTests[Cell[Id, StackF, Ω, Either[CellError, Ω], Ω]].monoid)
}
