package org.tessellation.schema

import cats.arrow.{Arrow, Category, Profunctor, Strong}
import cats.effect.Concurrent
import cats.free.{Coyoneda, Free, FreeT}
import cats.kernel.Monoid
import cats.implicits._
import cats.syntax.applicative._
import cats.syntax.functor._
import cats.{Applicative, Bimonad, Eq, Eval, Functor, MonoidK, Representable, Traverse, ~>}
import higherkindness.droste.syntax.compose._
import higherkindness.droste.util.DefaultTraverse
import higherkindness.droste._

/**
 * Terminal object
 */
sealed trait Ω

/**
 * Homomorphism object for determining morphism isomorphism
 */
sealed trait Hom[A, B] extends Ω {
  def tensor(x: Hom[A, B], y: Hom[A, B] = this): Hom[A, B]
}

case class Cocell[A, B](run: A => (Cocell[A, B], B), data: A*) extends Hom[A, B] {
  def tensor(x: Hom[A, B], y: Hom[A, B] = this): Cocell[A, B] = null
}


case class Cell[A, B](data: A*) extends Hom[A, B] {
  def op(init: B): Cocell[A, B] = null
  def tensor(x: Hom[A, B], y: Hom[A, B] = this): Cell[A, B] = null

}

case class Context[A, B]() extends Hom[A, B] {
  def tensor(x: Hom[A, B], y: Hom[A, B] = this): Context[A, B] = null
}

object Hom {
  def combine[F[_, _]: Arrow, A, B, C](fab: F[A, B], fac: F[A, C]): F[A, (B, C)] =
    Arrow[F].lift((a: A) => (a, a)) >>> (fab *** fac)

  def runList[A, B](ff: Cocell[A, B], as: List[A]): List[B] = as match {
    case h :: t =>
      val (ff2, b) = ff.run(h)
      b :: runList(ff2, t)
    case _ => List()
  }

  implicit val arrowInstance: Arrow[Cocell] = new Arrow[Cocell] {

    override def lift[A, B](f: A => B): Cocell[A, B] = Cocell(lift(f) -> f(_), Nil)

    override def first[A, B, C](fa: Cocell[A, B]): Cocell[(A, C), (B, C)] = Cocell {case (a, c) =>
      val (fa2, b) = fa.run(a)
      (first(fa2), (b, c))
    }

    override def id[A]: Cocell[A, A] = Cocell(id -> _)

    override def compose[A, B, C](f: Cocell[B, C], g: Cocell[A, B]): Cocell[A, C] = Cocell { a =>
      val (gg, b) = g.run(a)
      val (ff, c) = f.run(b)
      (compose(ff, gg), c)
    }
  }

  /**
   * For traversing allong Enrichment
   * @tparam A Fixed type
   * @return
   */
  implicit def drosteTraverseForHom[A]: Traverse[Hom[A, *]] =
    new DefaultTraverse[Hom[A, *]] {
      def traverse[F[_] : Applicative, B, C](fb: Hom[A, B])(f: B => F[C]): F[Hom[A, C]] =
        fb match {
//          case Cocell(op, tail) => f(op(tail: A)._2).map(Cocell(op, _))
          case Cocell(op, Nil) => (Context(): Hom[A, C]).pure[F]
          case Cell(value) => (Cell(value): Hom[A, C]).pure[F]
          case Context() => (Context(): Hom[A, C]).pure[F]
        }
    }

  def toScalaList[A, PatR[_[_]]](list: PatR[Hom[A, *]])(
    implicit ev: Project[Hom[A, *], PatR[Hom[A, *]]]
  ): List[A] =
    scheme.cata(toScalaListAlgebra[A]).apply(list)

  def toScalaListAlgebra[A]: Algebra[Hom[A, *], List[A]] = Algebra {
    case co@Cocell(op, Nil) => Nil
    case co@Cocell(op, thing) => thing :: Nil
    case Cell(thing) => thing :: Nil
    case Context() => Nil
  }

  def fromScalaList[A, PatR[_[_]]](list: List[A])(
    implicit ev: Embed[Hom[A, *], PatR[Hom[A, *]]]
  ): PatR[Hom[A, *]] =
    scheme.ana(fromScalaListCoalgebra[A]).apply(list)

  def fromScalaListCoalgebra[A]: Coalgebra[Hom[A, *], List[A]] = Coalgebra {
    case head :: Nil => Cell(head)
//    case head :: tail => tail.map(Cell(_)).fold(Cell(head))((l, r) => l.tensor(r)) // TODO
    //Cell(head).tensor(Cell(tail))
    case Nil => Context()
  }

  implicit def basisHomMonoid[T, A](implicit T: Basis[Hom[A, *], T]): Monoid[T] =
    new Monoid[T] {
      def empty = T.algebra(Context())

      def combine(f1: T, f2: T): T = {
        scheme
          .cata(Algebra[Hom[A, *], T] {
            case Context() => f2
            case cons => T.algebra(cons)
          })
          .apply(f1)
      }
    }

  implicit def drosteDelayEqHom[A](implicit eh: Eq[A]): Delay[Eq, Hom[A, *]] =
    λ[Eq ~> (Eq ∘ Hom[A, *])#λ](et =>
      Eq.instance((x, y) =>
        x match {
          case Cocell(hx, tx) =>
            y match {
              case Cocell(hy, ty) => eh.eqv(tx, ty) //&& et.eqv(hx, hy)
              case Context() => false
            }
          case Context() =>
            y match {
              case Context() => true
              case _ => false
            }
        }))

  def ifEndo[A](g: A => A, pred: A => Boolean): A => A = { //todo use for lifts
    a =>
      val newA = g(a)
      if (pred(newA)) newA else a
  }

  def accum[A, B](b: B)(f: (A, B) => B): Cocell[A, B] = Cocell { a =>
    val b2 = f(a, b)
    (accum(b2)(f), b2)
  }

  def sum[A: Monoid]: Cocell[A, A] = accum(Monoid[A].empty)(_ |+| _)
  def count[A]: Cocell[A, Int] = Arrow[Cocell].lift((_: A) => 1) >>> sum
  def avg: Cocell[Int, Double] =
    combine(sum[Int], count[Int]) >>> Arrow[Cocell].lift{case (x, y) => x.toDouble / y}
}


/**
 * Topos context
 */
trait Topos[C[_, _]] extends Category[C]{//todo use lambdas A <-> O here
  // finite limits should exist
  def tensor(x: this.type , y: this.type): this.type
  // subobject classifier
  val Ω: this.type
  def pow: this.type => this.type
}

object Topos {
  type FreeF[S[_], A] = Free[Coyoneda[S, *], A]
  type Enriched[O] = Topos[Hom[O, *]]

  implicit val rep = new Representable[Enriched] {
    override def F: Functor[Enriched] = ???

    override type Representation = this.type

    override def index[A](f: Enriched[A]): this.type => A = ???
    // https://ncatlab.org/nlab/show/2-sheaf
    // https://ncatlab.org/nlab/show/indexed+category

    override def tabulate[A](f: this.type => A): Enriched[A] = ???
  }

  def inject[F[_], G[_]](transformation: F ~> G) =
    new (FreeF[F, *] ~> FreeF[G, *]) {//transformation of free algebras
      def apply[A](fa: FreeF[F, A]): FreeF[G, A] =
        fa.mapK[Coyoneda[G, *]](
          new (Coyoneda[F, *] ~> Coyoneda[G, *]) {
            def apply[B](fb: Coyoneda[F, B]): Coyoneda[G, B] = fb.mapK(transformation)
          }
        )
    }

  implicit val monoidK = new MonoidK[Enriched] {
    override def empty[A]: Enriched[A] = ???

    override def combineK[A](x: Enriched[A], y: Enriched[A]): Enriched[A] = ???
  }
}

abstract class Fiber[A, B]

abstract class Bundle[F, G](fibers: F)

abstract class Simplex[T, U, V](fibers: Seq[Hom[T, U]])
