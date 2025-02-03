package io.constellationnetwork.dag.l0.domain.cell

import cats.effect.IO
import cats.effect.std.Queue

import io.constellationnetwork.block.generators.signedBlockGen
import io.constellationnetwork.kernel.Cell
import io.constellationnetwork.schema.Block
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import weaver.SimpleMutableIOSuite
import weaver.scalacheck.Checkers

object L0CellSuite extends SimpleMutableIOSuite with Checkers {

  def mkL0CellMk(queue: Queue[IO, Signed[Block]]) =
    L0Cell.mkL0Cell[IO](queue, null, null)

  test("pass dag block to the queue") { _ =>
    forall(signedBlockGen) { dagBlock =>
      for {
        dagBlockQueue <- Queue.unbounded[IO, Signed[Block]]
        mkDagCell = mkL0CellMk(dagBlockQueue)
        cell = mkDagCell(L0CellInput.HandleDAGL1(dagBlock))
        res <- cell.run()
        sentData <- dagBlockQueue.tryTake
      } yield expect.same((res, sentData.get), (Right(Cell.NullTerminal), dagBlock))
    }
  }
}
