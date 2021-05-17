package org.tessellation.consensus

import cats.{Applicative, MonoidK}
import cats.effect.IO
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito}
import org.mockito.cats.IdiomaticMockitoCats
import org.scalatest.BeforeAndAfter
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.tessellation.consensus.L1ConsensusStep.L1ConsensusMetadata
import org.tessellation.schema.Cell.{NullTerminal, cellMonoid}
import org.tessellation.schema.{Cell, CellError, StackF, Ω}


case class SampleData() extends Ω

case class SampleOutput() extends Ω

class CellMonoidTest extends AnyFreeSpec
  with IdiomaticMockito
  with IdiomaticMockitoCats
  with Matchers
  with ArgumentMatchersSugar
  with BeforeAndAfter {

  "empty" - {
    implicit val M: MonoidK[Cell[IO, StackF, Ω, Either[CellError, Ω], *]] = Cell.cellMonoid[IO, StackF]

    "creates empty" in {
      val empty = M.empty[(Int, Int)]
      empty.data.isInstanceOf[NullTerminal] shouldBe true
    }

    "empty hylo is just an identity" in {
      val empty = M.empty[(Int, Int)]
      val hylo = empty.hyloM(_ => (1, 1))
      val input = empty.data
      val output = hylo.unsafeRunSync()
      output shouldBe Right(input)
    }

    "combine is an identity function (right empty)" in {
      val l1Cell = L1Cell(L1Edge(Set.empty[L1Transaction]))
      val emptyCell = M.empty[(L1ConsensusMetadata, Ω)]
      val combined = M.combineK[(L1ConsensusMetadata, Ω)](
        l1Cell.asInstanceOf[Cell[IO, StackF, Ω, Either[CellError, Ω], (L1ConsensusMetadata, Ω)]],
        emptyCell
      )

      combined shouldBe l1Cell
    }

    "combine is an identity function (left empty)" in {
      val l1Cell = L1Cell(L1Edge(Set.empty[L1Transaction]))
      val emptyCell = M.empty[(L1ConsensusMetadata, Ω)]
      val combined = M.combineK[(L1ConsensusMetadata, Ω)](
        emptyCell,
        l1Cell.asInstanceOf[Cell[IO, StackF, Ω, Either[CellError, Ω], (L1ConsensusMetadata, Ω)]],
      )

      combined shouldBe l1Cell
    }
  }

}