package org.tessellation.snapshot

import cats.effect.IO
import cats.syntax.all._
import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}
import org.tessellation.schema._

object L0Snapshot { // TODO: make it generic and reuse together L1Consensus and L0Snapshot

  val coalgebra: CoalgebraM[IO, StackF, Ω] = CoalgebraM {
    case end @ Snapshot(_) =>
      IO {
        Done(end.asRight[CellError])
      }
    case _ @L0Error(reason) =>
      IO {
        Done(CellError(reason).asLeft[Ω])
      }
    case cmd =>
      scheme.hyloM(L0SnapshotStep.algebra, L0SnapshotStep.coalgebra).apply(cmd).map {
        c =>
          if (c.isLeft) {
            Done(CellError(c.left.get.reason).asLeft[Ω])
          } else {
            More(c.right.get)
          }
      }
  }

  val algebra: AlgebraM[IO, StackF, Either[CellError, Ω]] = AlgebraM {
    case More(a) => IO(a)
    case Done(result) => IO(result)
  }

  def hyloM: Ω => IO[Either[CellError, Ω]] = scheme.hyloM(algebra, coalgebra)
}
