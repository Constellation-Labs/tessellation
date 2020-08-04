package org.tessellation.schema

import cats.arrow.Arrow
import cats.implicits._
import cats.kernel.{Monoid, PartialOrder}
import cats.{Applicative, Bifunctor, Eq, Functor, Traverse, ~>}
import higherkindness.droste.syntax.compose._
import higherkindness.droste.util.DefaultTraverse
import higherkindness.droste.{Algebra, _}

/**
  * Characteristic Sheaf just needs to be Poset
  */
trait Poset extends PartialOrder[Ω] {
  override def partialCompare(x: Ω, y: Ω): Double = if (x == y) 0.0 else 1.0
}

/**
  * Terminal object
  */
trait Ω extends Poset

/**
  * Homomorphism object for determining morphism isomorphism
  */
trait Hom[+A, +B] extends Ω

object Hom {
  import cats.syntax.applicative._
  import cats.syntax.functor._

  def empty[A] = new Hom[A, A] {}

  /**
    * For traversing allong Enrichment
    *
    * @tparam A Fixed type
    * @return
    */
  implicit def drosteTraverseForHom[A]: Traverse[Hom[A, *]] =
    new DefaultTraverse[Hom[A, *]] {
      def traverse[F[_]: Applicative, B, C](
        fb: Hom[A, B]
      )(f: B => F[C]): F[Hom[A, C]] =
        fb match {
          case Cell2(head, tail) => f(tail).map(Cell2(head, _))
          case Cell(value)         => (Cell(value): Hom[A, C]).pure[F]
          case Context()           => (Context(): Hom[A, C]).pure[F]
        }
    }

  def toScalaList[A, PatR[_[_]]](
    list: PatR[Hom[A, *]]
  )(implicit ev: Project[Hom[A, *], PatR[Hom[A, *]]]): List[A] =
    scheme.cata(toScalaListAlgebra[A]).apply(list)

  def toScalaListAlgebra[A]: Algebra[Hom[A, *], List[A]] = Algebra {
    case Cell2(head, tail) => head :: tail
    case Cell(thing)         => thing :: Nil
    case Context()           => Nil
  }

  def fromScalaList[A, PatR[_[_]]](
    list: List[A]
  )(implicit ev: Embed[Hom[A, *], PatR[Hom[A, *]]]): PatR[Hom[A, *]] =
    scheme.ana(fromScalaListCoalgebra[A]).apply(list)

  def fromScalaListCoalgebra[A]: Coalgebra[Hom[A, *], List[A]] = Coalgebra {
    case head :: Nil  => Cell(head)
    case head :: tail => Cell2(head, tail)
    case Nil          => Context()
  }

  implicit def basisHomMonoid[T, A](
    implicit T: Basis[Hom[A, *], T]
  ): Monoid[T] =
    new Monoid[T] {
      def empty = T.algebra(Context())

      def combine(f1: T, f2: T): T = {
        scheme
          .cata(Algebra[Hom[A, *], T] {
            case Context() => f2
            case cons      => T.algebra(cons)
          })
          .apply(f1)
      }
    }

  // todo this is where we use Poset to order events
  implicit def drosteDelayEqHom[A](implicit eh: Eq[A]): Delay[Eq, Hom[A, *]] =
    λ[Eq ~> (Eq ∘ Hom[A, *])#λ](
      et =>
        Eq.instance(
          (x, y) =>
            x match {
              case Cell2(hx, tx) =>
                y match {
                  case Cell2(hy, ty) => eh.eqv(hx, hy) && et.eqv(tx, ty)
                  case Context()       => false
                }
              case Context() =>
                y match {
                  case Context() => true
                  case _         => false
                }
          }
      )
    )

  def ifEndo[A](g: A => A, pred: A => Boolean): A => A = { //todo use for lifts
    a =>
      val newA = g(a)
      if (pred(newA)) newA else a
  }
}

case class Cell[A, B](a: A) extends Hom[A, B]{
  def run(a: A = a)(implicit cocell: Cocell[A, B]) = cocell.run(a)
  def b(a: A)(implicit cocell: Cocell[A, B]): B = run(a)._2
  def morphism(f: A => B)(implicit cocell: Cocell[A, B]) = Cell2(a, f(a))
}

case class Cell2[A, B](a: A, b: B) extends Topos[A, B]

case class Context() extends Hom[Nothing, Nothing]

//todo convert to => Either[Cocell[A, B], B]
case class Cocell[A, B](run: A => (Cocell[A, B], B)) extends Hom[A, B]

object Cocell {
  implicit val arrowInstance: Arrow[Cocell] = new Arrow[Cocell] {

    override def lift[A, B](f: A => B): Cocell[A, B] = Cocell(lift(f) -> f(_))

    override def first[A, B, C](fa: Cocell[A, B]): Cocell[(A, C), (B, C)] =
      Cocell {
        case (a, c) =>
          val (fa2, b) = fa.run(a)
          (first(fa2), (b, c))
      }

    override def compose[A, B, C](f: Cocell[B, C],
                                  g: Cocell[A, B]): Cocell[A, C] = Cocell { a =>
      val (gg, b) = g.run(a)
      val (ff, c) = f.run(b)
      (compose(ff, gg), c)
    }
  }

  def runList[A, B](ff: Cocell[A, B], as: List[A]): List[B] = as match {
    case h :: t =>
      val (ff2, b) = ff.run(h)
      b :: runList(ff2, t)
    case _ => List()
  }

  def accum[A, B](b: B)(f: (A, B) => B): Cocell[A, B] = Cocell { a =>
    val b2 = f(a, b)
    (accum(b2)(f), b2)
  }

  def sum[A: Monoid]: Cocell[A, A] = accum(Monoid[A].empty)(_ |+| _)
  def count[A]: Cocell[A, Int] = Arrow[Cocell].lift((_: A) => 1) >>> sum
  def avg: Cocell[Int, Double] =
    Topos.combine(sum[Int], count[Int]) >>> Arrow[Cocell].lift {
      case (x, y) => x.toDouble / y
    }
}

abstract class Fiber[A, B]

abstract class Bundle[F, G](fibers: F)

abstract class Simplex[T, U, V](fibers: Seq[Hom[T, U]])
