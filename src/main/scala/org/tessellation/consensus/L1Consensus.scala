package org.tessellation.consensus

import cats.effect.IO
import cats.syntax.all._
import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}
import org.tessellation.consensus.L1ConsensusStep.L1ConsensusMetadata
import org.tessellation.schema.{CellError, Done, More, StackF, Ω}

case class L1CoalgebraStruct(metadata: L1ConsensusMetadata, cmd: Ω) extends Ω

object L1Consensus {

  val coalgebra: CoalgebraM[IO, StackF, L1CoalgebraStruct] = CoalgebraM {
    case L1CoalgebraStruct(metadata, cmd) =>
      cmd match {
        case block@L1Block(_) =>
          IO {
            Done(block.asRight[CellError])
          }
        case end@ConsensusEnd(_) =>
          IO {
            Done(end.asRight[CellError])
          }
        case response@ProposalResponse(_) =>
          IO {
            Done(response.asRight[CellError])
          }
        case _ @L1Error(reason) =>
          IO {
            Done(CellError(reason).asLeft[Ω])
          }
        case _ =>
          scheme.hyloM(L1ConsensusStep.algebra, L1ConsensusStep.coalgebra).apply(cmd).run(metadata).map {
            case (m, cmd) =>
              if (cmd.isLeft) {
                Done(CellError(cmd.left.get.reason).asLeft[Ω]) // TODO: Get rid of `get`
              } else {
                More(L1CoalgebraStruct(m, cmd.right.get)) // TODO: Get rid of `get`
              }
          }
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

  def hyloM: L1CoalgebraStruct => IO[Either[CellError, Ω]] = scheme.hyloM(algebra, coalgebra)
}
