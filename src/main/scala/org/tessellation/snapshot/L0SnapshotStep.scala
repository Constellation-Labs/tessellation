package org.tessellation.snapshot

import cats.effect.IO
import cats.syntax.all._
import higherkindness.droste.{AlgebraM, CoalgebraM}
import org.tessellation.consensus.L1Block
import org.tessellation.schema.{CellError, Ω}

object L0SnapshotStep {

  /*
  1. check disk space
  2. validate max accepted blocks in memory
  3. validate accepted blocks since snapshot
  4. get next height interval
  5. get min active tip height
  6. get min tip height
  7. get blocks within height interval
  8. get full blocks
  9. get hashes for next snapshot
  10. get public reputation
  11. create next snapshot
  12. update cache using next snapshot
  13. apply snapshot on bounded context
  14. set last snapshot height
  15. filter out accepted blocks since last snapshot
  16. calculate accepted transactions since latest snapshot
  17. store snapshot on disk
  18. mark leaving peers as offline
  19. remove offline peers
   */

  /**
    * Coalgebra should get input from any state channel because all the data types inherit from Ω
    * Ω types should be then pattern matched to decide what to do with specific types of block.
    * We can eventually create intermediate types like ΩBlock to make L0 more type-strict but we pattern match anyway.
    */
  val coalgebra: CoalgebraM[IO, L0SnapshotF, Ω] = CoalgebraM {
    case CreateSnapshot(edge) =>
      for {
        _ <- IO.pure(1)
      } yield SnapshotEnd(edge.blocks.asInstanceOf[Set[L1Block]]) // TODO: Get rid of casting
  }

  val algebra: AlgebraM[IO, L0SnapshotF, Either[CellError, Ω]] = AlgebraM {
    case SnapshotEnd(blocks) =>
      IO {
        Snapshot(blocks).asRight[CellError]
      }

    case L0Error(reason) =>
      IO {
        CellError(reason).asLeft[Ω]
      }

    case cmd: Ω =>
      IO { cmd.asRight[CellError] }
  }

}
