package org.tessellation.eth.hylo

import cats.effect.IO
import cats.implicits._
import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}
import org.tessellation.consensus.L1Block
import org.tessellation.schema._

object ETHStackHylomorphism {

  val coalgebra: CoalgebraM[IO, StackF, Ω] = CoalgebraM {
    case block @ L1Block(_) =>
      IO(Done(block.asRight[CellError]))

    case error @ CellError(_) =>
      IO(Done(error.asLeft[Ω]))

    case input =>
      scheme.hyloM(ETHHylomorphism.algebra, ETHHylomorphism.coalgebra).apply(input).map {
        case Left(value)  => Done(value.asLeft[Ω])
        case Right(value) => More(value)
      }
  }

  val algebra: AlgebraM[IO, StackF, Either[CellError, Ω]] = AlgebraM {
    case More(a) =>
      IO {
        a
      }
    case Done(result) =>
      IO {
        result
      }
  }

  def hyloM: Ω => IO[Either[CellError, Ω]] = scheme.hyloM(algebra, coalgebra)
}
