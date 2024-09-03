package io.constellationnetwork.kernel

import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.functor._
import cats.{Applicative, Traverse}

import higherkindness.droste.util.DefaultTraverse

case class CellError(reason: String) extends Throwable(reason) with Ω

sealed trait StackF[A]

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

  implicit val applicative: Applicative[StackF] = new Applicative[StackF] {
    override def pure[A](x: A): StackF[A] =
      x match {
        case terminal: Ω => Done(terminal.asRight[CellError])
        case _           => Done(CellError("Non terminal type").asLeft)
      }

    override def ap[A, B](ff: StackF[A => B])(fa: StackF[A]): StackF[B] =
      (ff, fa) match {
        case (More(ff), More(fa)) => More(ff(fa))
        case (_, Done(fa))        => Done(fa)
        case (Done(ff), _)        => Done(ff)
      }
  }
}
