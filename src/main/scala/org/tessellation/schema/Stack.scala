package org.tessellation.schema

import cats.syntax.all._
import cats.{Applicative, Traverse}
import higherkindness.droste.util.DefaultTraverse

case class CellError(reason: String) extends Throwable(reason)

trait StackF[A]

case class More[A](a: A) extends StackF[A]
case class Done[A](result: Either[CellError, Ω]) extends StackF[A]

object StackF {
  implicit val traverse: Traverse[StackF] = new DefaultTraverse[StackF] {
    override def traverse[G[_]: Applicative, A, B](fa: StackF[A])(f: A => G[B]): G[StackF[B]] =
      fa match {
        case More(a) => f(a).map(More(_))
        case Done(r) => (Done(r): StackF[B]).pure[G]
      }
  }
}
