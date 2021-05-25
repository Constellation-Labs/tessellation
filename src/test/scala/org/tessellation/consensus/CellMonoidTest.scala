package org.tessellation.consensus

import cats.{Applicative, Monoid, MonoidK}
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
import org.tessellation.schema.{Cell, CellError, StackF, Ω, ΩList}
import org.tessellation.snapshot.{L0Cell, L0Edge, Snapshot}
import cats.syntax._

class CellMonoidTest
    extends AnyFreeSpec
    with IdiomaticMockito
    with IdiomaticMockitoCats
    with Matchers
    with ArgumentMatchersSugar
    with BeforeAndAfter {

  implicit val M: Monoid[Cell[IO, StackF, Ω, Either[CellError, Ω], Ω]] = Cell.cellMonoid[IO, StackF]

  "empty" - {
    "creates empty" in {
      val empty = M.empty
      empty.data.isInstanceOf[NullTerminal] shouldBe true
    }

    "empty hylo is just an identity" in {
      val empty = M.empty
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
      val emptyCell = M.empty
      val combined = M.combine(
        l1Cell,
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
      val emptyCell = M.empty
      val combined = M.combine(
        emptyCell,
        l1Cell
      )

      combined shouldBe l1Cell
    }

    "combine same types" in {
      val metadata = L1ConsensusMetadata.empty(
        L1ConsensusContext(
          Node("A", RandomTransactionGenerator("A")),
          Set.empty[Node],
          RandomTransactionGenerator("A")
        )
      )
      val txA = L1Transaction(1, "a", "b", "", 0)
      val l1CellA = L1StartConsensusCell(L1Edge(Set(txA)), metadata)
      val txB = L1Transaction(1, "b", "c", "", 0)
      val l1CellB = L1StartConsensusCell(L1Edge(Set(txB)), metadata)

      val resultA = l1CellA.run().unsafeRunSync()
      val resultB = l1CellB.run().unsafeRunSync()
      val expectedResult = resultA.flatMap(a => resultB.map(b => a :: b))

      val combined = M.combine(
        l1CellA,
        l1CellB
      )

      val result = combined.run().unsafeRunSync()

      result shouldBe expectedResult
    }

    "combine different types" in {
      val metadata = L1ConsensusMetadata.empty(
        L1ConsensusContext(
          Node("A", RandomTransactionGenerator("A")),
          Set.empty[Node],
          RandomTransactionGenerator("A")
        )
      )
      val l1Cell = L1StartConsensusCell(L1Edge(Set.empty[L1Transaction]), metadata)
      val l0Cell = L0Cell(L0Edge(Set.empty[L1Block]))

      val l1Result = l1Cell.run().unsafeRunSync()
      val l0Result = l0Cell.run().unsafeRunSync()
      val expectedResult = l1Result.flatMap(a => l0Result.map(b => a :: b))

      // TODO: Investigate why syntax doesn't work
      //      val combined  = l1Cell |+| l0Cell
      val combined = M.combine(l1Cell, l0Cell)

      val result = combined.run().unsafeRunSync()

      println(result)

      result shouldBe expectedResult
    }
  }

}
