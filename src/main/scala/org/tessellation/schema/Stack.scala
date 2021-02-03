package org.tessellation.schema

import cats.data.StateT
import cats.effect.IO
import cats.{Applicative, Traverse}
import cats.syntax.all._
import higherkindness.droste.{Algebra, AlgebraM, Coalgebra, CoalgebraM, scheme}
import higherkindness.droste.util.DefaultTraverse
import org.tessellation.schema.L1Consensus.{L1ConsensusMetadata, StateM, algebra, coalgebra}

trait StackF[A]

case class More[A](a: A) extends StackF[A]
case class Done[A](result: Ω) extends StackF[A]

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
  val coalgebra: CoalgebraM[IO, StackF, (L1ConsensusMetadata, Ω)] = CoalgebraM { in =>
    in._2 match {
      case block @ L1Block(_) => IO { Done(block) }
      case _ => scheme.hyloM(L1Consensus.algebra, L1Consensus.coalgebra).apply(in._2).run(in._1).map(More(_))
    }
  }

  val algebra: AlgebraM[IO, StackF, Ω] = AlgebraM {
    case More(a) => IO { a }
    case Done(result) => IO { result }
  }
}
