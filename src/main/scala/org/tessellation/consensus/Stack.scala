package org.tessellation.consensus

import cats.syntax.all._
import cats.{Applicative, Traverse}
import higherkindness.droste.util.DefaultTraverse
import org.tessellation.consensus.L1ConsensusStep.L1ConsensusError
import org.tessellation.schema.Ω

trait StackF[A]

case class More[A](a: A) extends StackF[A]
case class Done[A](result: Either[L1ConsensusError, Ω]) extends StackF[A] // TODO: make it generic and remove parametrized either

object StackF {
  implicit val traverse: Traverse[StackF] = new DefaultTraverse[StackF] {
    override def traverse[G[_]: Applicative, A, B](fa: StackF[A])(f: A => G[B]): G[StackF[B]] =
      fa match {
        case More(a) => f(a).map(More(_))
        case Done(r) => (Done(r): StackF[B]).pure[G]
      }
  }
}
