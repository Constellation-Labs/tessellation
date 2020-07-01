package org.tessellation.schema

import cats.{Applicative, Eq, Monoid, Traverse}
import cats.effect.Concurrent
import cats.syntax.applicative._
import cats.syntax.functor._
import higherkindness.droste.util.DefaultTraverse
import higherkindness.droste.{Algebra, Basis, Coalgebra, Delay, Embed, Project, scheme, syntax, util}

//todo put edge/signing stuff here
sealed trait Hom[+A, +B] {
  //  val op: A => B

  //  def observe = op(data)

  def tensor(x: Hom[_, _], y: Hom[_, _] = this): Hom[A, B] = unit //https://ncatlab.org/nlab/show/Boardman-Vogt+tensor+product

  def unit: Hom[A, B] = this
}

abstract class Fiber[A, B] extends Hom[A, B]

abstract class Bundle[F, G](fibers: F) extends Fiber[F, G]

abstract class Simplex[T, U, V](fibers: Seq[Hom[T, U]]) extends Hom[U, V]

case class CellT[F[_] : Concurrent, A](value: A) {}

case class Cell[A, B](value: A) extends Hom[A, B]

case class Cocell[A, B](value: A, stateTransitionEval: B) extends Hom[A, B]

case object Context extends Hom[Nothing, Nothing]

object Hom {
  implicit def drosteTraverseForHom[A]: Traverse[Hom[A, ?]] =
    new DefaultTraverse[Hom[A, ?]] {
      def traverse[F[_] : Applicative, B, C](fb: Hom[A, B])(f: B => F[C]): F[Hom[A, C]] =
        fb match {
          case Cocell(head, tail) => f(tail).map(Cocell(head, _))
          case Cell(value) => (Cell(value): Hom[A, C]).pure[F]
          case Context => (Context: Hom[A, C]).pure[F]
        }
    }

  def toScalaList[A, PatR[_[_]]](list: PatR[Hom[A, ?]])(
    implicit ev: Project[Hom[A, ?], PatR[Hom[A, ?]]]
  ): List[A] =
    scheme.cata(toScalaListAlgebra[A]).apply(list)

  def toScalaListAlgebra[A]: Algebra[Hom[A, ?], List[A]] = Algebra {
    case Cocell(head, tail) => head :: tail
    case Cell(thing) => thing :: Nil
    case Context => Nil
  }

  def fromScalaList[A, PatR[_[_]]](list: List[A])(
    implicit ev: Embed[Hom[A, ?], PatR[Hom[A, ?]]]
  ): PatR[Hom[A, ?]] =
    scheme.ana(fromScalaListCoalgebra[A]).apply(list)

  def fromScalaListCoalgebra[A]: Coalgebra[Hom[A, ?], List[A]] = Coalgebra {
    case head :: Nil => Cell(head)
    case head :: tail => Cocell(head, tail)
    case Nil => Context
  }

  implicit def basisHomMonoid[T, A](
                                     implicit T: Basis[Hom[A, ?], T]): Monoid[T] =
    new Monoid[T] {
      def empty = T.algebra(Context)

      def combine(f1: T, f2: T): T = {
        scheme
          .cata(Algebra[Hom[A, ?], T] {
            case Context => f2
            case cons => T.algebra(cons)
          })
          .apply(f1)
      }
    }

  import cats.~>
  import syntax.compose._

  implicit def drosteDelayEqHom[A](implicit eh: Eq[A]): Delay[Eq, Hom[A, ?]] =
    λ[Eq ~> (Eq ∘ Hom[A, ?])#λ](et =>
      Eq.instance((x, y) =>
        x match {
          case Cocell(hx, tx) =>
            y match {
              case Cocell(hy, ty) => eh.eqv(hx, hy) && et.eqv(tx, ty)
              case Context => false
            }
          case Context =>
            y match {
              case Context => true
              case _ => false
            }
        }))

  def ifEndo[A](g: A => A, pred: A => Boolean): A => A = { //todo use for lifts
    a =>
      val newA = g(a)
      if (pred(newA)) newA else a
  }
}
