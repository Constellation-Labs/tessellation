package org.tessellation.schema

import cats.data.StateT
import cats.effect.IO
import cats.{Applicative, Traverse}
import cats.syntax.all._
import higherkindness.droste.{Algebra, AlgebraM, Coalgebra, CoalgebraM, scheme}
import higherkindness.droste.util.DefaultTraverse
import org.tessellation.schema.L1Consensus.{
  L1ConsensusContext,
  L1ConsensusError,
  L1ConsensusMetadata,
  StateM,
  algebra,
  coalgebra
}

trait StackF[A]

case class More[A](a: A) extends StackF[A]
case class Done[A](result: Either[L1ConsensusError, Ω]) extends StackF[A]

object StackF {
  implicit val traverse: Traverse[StackF] = new DefaultTraverse[StackF] {
    override def traverse[G[_]: Applicative, A, B](fa: StackF[A])(f: A => G[B]): G[StackF[B]] =
      fa match {
        case More(a) => f(a).map(More(_))
        case Done(r) => (Done(r): StackF[B]).pure[G]
      }
  }
}

object StackL1Consensus {

  val coalgebra: CoalgebraM[IO, StackF, (L1ConsensusMetadata, Ω)] = CoalgebraM {
    case (metadata, cmd) =>
      cmd match {
        case block @ L1Block(_)             => IO { Done(block.asRight[L1ConsensusError]) }
        case end @ ConsensusEnd(_)          => IO { Done(end.asRight[L1ConsensusError]) }
        case response @ ProposalResponse(_) => IO { Done(response.asRight[L1ConsensusError]) }
        case _ @L1Error(reason)             => IO { Done(L1ConsensusError(reason).asLeft[Ω]) }
        case _ =>
          scheme.hyloM(L1Consensus.algebra, L1Consensus.coalgebra).apply(cmd).run(metadata).map {
            case (m, cmd) => {
              if (cmd.isLeft) {
                Done(L1ConsensusError(cmd.left.get.reason).asLeft[Ω])
              } else {
                More((m, cmd.right.get))
              }
            }
          }
      }
  }

  val algebra: AlgebraM[IO, StackF, Either[L1ConsensusError, Ω]] = AlgebraM {
    case More(a)      => IO { a }
    case Done(result) => IO { result }
  }
}
