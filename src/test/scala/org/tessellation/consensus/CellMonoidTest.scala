package org.tessellation.consensus

import cats.{Applicative, MonoidK}
import cats.effect.IO
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito}
import org.mockito.cats.IdiomaticMockitoCats
import org.scalatest.BeforeAndAfter
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.tessellation.Node
import org.tessellation.consensus.L1ConsensusStep.{L1ConsensusContext, L1ConsensusMetadata}
import org.tessellation.consensus.transaction.RandomTransactionGenerator
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
      val hylo = empty.run()
      val input = empty.data
      val output = hylo.unsafeRunSync()
      output shouldBe Right(input)
    }

    "combine is an identity function (right empty)" in {
      val metadata = L1ConsensusMetadata.empty(
        L1ConsensusContext(
          Node("A", RandomTransactionGenerator("A")),
          Set.empty[Node],
          RandomTransactionGenerator("A")
        )
      )
      val l1Cell = L1StartConsensusCell(L1Edge(Set.empty[L1Transaction]), metadata)
      val emptyCell = M.empty[(L1ConsensusMetadata, Ω)]
      val combined = M.combineK[(L1ConsensusMetadata, Ω)](
        l1Cell.asInstanceOf[Cell[IO, StackF, Ω, Either[CellError, Ω], (L1ConsensusMetadata, Ω)]],
        emptyCell
      )

      combined shouldBe l1Cell
    }

    "combine is an identity function (left empty)" in {
      val metadata = L1ConsensusMetadata.empty(
        L1ConsensusContext(
          Node("A", RandomTransactionGenerator("A")),
          Set.empty[Node],
          RandomTransactionGenerator("A")
        )
      )
      val l1Cell = L1StartConsensusCell(L1Edge(Set.empty[L1Transaction]), metadata)
      val emptyCell = M.empty[(L1ConsensusMetadata, Ω)]
      val combined = M.combineK[(L1ConsensusMetadata, Ω)](
        emptyCell,
        l1Cell.asInstanceOf[Cell[IO, StackF, Ω, Either[CellError, Ω], (L1ConsensusMetadata, Ω)]],
      )

      combined shouldBe l1Cell
    }
  }

}