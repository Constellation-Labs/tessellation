package org.tessellation.schema

import cats.arrow.{Arrow, Category, FunctionK}
import cats.free.{Cofree, Coyoneda, Free}
import cats.implicits._
import cats.kernel.{Monoid}
import cats.{Applicative, Bifunctor, Eq, Eval, Functor, MonoidK, Representable, Traverse, ~>}
import higherkindness.droste._
import higherkindness.droste.syntax.compose._
import higherkindness.droste.util.DefaultTraverse
import org.tessellation.schema.Topos.{Enriched, FreeF}
import cats.kernel.PartialOrder


/**
  * Characteristic Sheaf just needs to be Poset
  */
trait Poset extends PartialOrder[Ω]{
  override def partialCompare(x: Ω,
                              y: Ω): Double = if (x == y) 0.0 else 1.0
}

/**
  * Terminal object
  */
trait Ω extends Poset

/**
  * Homomorphism object for determining morphism isomorphism
  */
trait Hom[+A, +B] extends Ω

case class Cocell[A, B](run: A => (Cocell[A, B], B)) extends Hom[A, B]

case class Cell[A, B](data: A) extends Hom[A, B] {
  def run: A => (Cocell[A, B], B) = null
  def toCocell = Cocell[A, B](run)
}

case class TwoCell[A, B](data: A, stateTransitionEval: B) extends Hom[A, B]{
  def run: A => (Cocell[A, B], B) = null
  def toCocell = Cocell[A, B](run)
}

case class Context() extends Hom[Nothing, Nothing]

object Hom {
  import cats.syntax.applicative._
  import cats.syntax.functor._

  def empty[A] = new Hom[A, A]{}

  def combine[F[_, _]: Arrow, A, B, C](fab: F[A, B],
                                       fac: F[A, C]): F[A, (B, C)] =
    Arrow[F].lift((a: A) => (a, a)) >>> (fab *** fac)

  def runList[A, B](ff: Cocell[A, B], as: List[A]): List[B] = as match {
    case h :: t =>
      val (ff2, b) = ff.run(h)
      b :: runList(ff2, t)
    case _ => List()
  }

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

  def accum[A, B](b: B)(f: (A, B) => B): Cocell[A, B] = Cocell { a =>
    val b2 = f(a, b)
    (accum(b2)(f), b2)
  }

  def sum[A: Monoid]: Cocell[A, A] = accum(Monoid[A].empty)(_ |+| _)
  def count[A]: Cocell[A, Int] = Arrow[Cocell].lift((_: A) => 1) >>> sum
  def avg: Cocell[Int, Double] =
    combine(sum[Int], count[Int]) >>> Arrow[Cocell].lift {
      case (x, y) => x.toDouble / y
    }

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
          case TwoCell(head, tail) => f(tail).map(TwoCell(head, _))
          case Cell(value)         => (Cell(value): Hom[A, C]).pure[F]
          case Context()           => (Context(): Hom[A, C]).pure[F]
        }
    }

  def toScalaList[A, PatR[_[_]]](
    list: PatR[Hom[A, *]]
  )(implicit ev: Project[Hom[A, *], PatR[Hom[A, *]]]): List[A] =
    scheme.cata(toScalaListAlgebra[A]).apply(list)

  def toScalaListAlgebra[A]: Algebra[Hom[A, *], List[A]] = Algebra {
    case TwoCell(head, tail) => head :: tail
    case Cell(thing)         => thing :: Nil
    case Context()           => Nil
  }

  def fromScalaList[A, PatR[_[_]]](
    list: List[A]
  )(implicit ev: Embed[Hom[A, *], PatR[Hom[A, *]]]): PatR[Hom[A, *]] =
    scheme.ana(fromScalaListCoalgebra[A]).apply(list)

  def fromScalaListCoalgebra[A]: Coalgebra[Hom[A, *], List[A]] = Coalgebra {
    case head :: Nil  => Cell(head)
    case head :: tail => TwoCell(head, tail)
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

  implicit def drosteDelayEqHom[A](implicit eh: Eq[A]): Delay[Eq, Hom[A, *]] =
    λ[Eq ~> (Eq ∘ Hom[A, *])#λ](
      et =>
        Eq.instance(
          (x, y) =>
            x match {
              case TwoCell(hx, tx) =>
                y match {
                  case TwoCell(hy, ty) => eh.eqv(hx, hy) && et.eqv(tx, ty)
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

/**
  * Topos context
  */
trait Topos extends Arrow[Hom] with Ω { //todo use lambdas A <-> O here
  val terminator: Ω = this  // subobject classifier
  def pow: Ω => Ω = _ => this
  val repr: Representable[Enriched]// finite limits should exist
  override def id[A]: Hom[A, A] = Hom.empty[A]
  override def compose[A, B, C](
      f: Hom[B, C],
      g: Hom[A, B]
    ): Hom[A, C] = ??? //todo add run method/val in Hom, and define as mix of algebra/coalgebra
  override def lift[A, B](f: A => B): Hom[A, B] =
    ???
  override def first[A, B, C](
    fa: Hom[A, B]
  ): Hom[(A, C), (B, C)] = ???
}

object Topos {
  type FreeF[S[_], A] = Free[Coyoneda[S, ?], A]
  type Enriched[A] = FreeF[Hom[?, A], A]
  //todo map between recursion schemes and Cofree, define morphisms and colimits with lambdas below ->
  //  val coAttr = Coattr.fromCats()
  //  val listToOption = λ[FunctionK[List, Option]](_.headOption)

  /**
    * FunctionK but with a CoYoneda decomposition
    * @param transformation
    * @tparam F
    * @tparam G
    * @return
    */
  implicit def inject[F[_], G[_]](transformation: F ~> G) =
    new (FreeF[F, *] ~> FreeF[G, *]) { //transformation of free algebras
      def apply[A](fa: FreeF[F, A]): FreeF[G, A] =
        fa.mapK[Coyoneda[G, *]](new (Coyoneda[F, *] ~> Coyoneda[G, *]) {
          def apply[B](fb: Coyoneda[F, B]): Coyoneda[G, B] =
            fb.mapK(transformation)
        })
    }
}

abstract class Fiber[A, B]

abstract class Bundle[F, G](fibers: F)

abstract class Simplex[T, U, V](fibers: Seq[Hom[T, U]])
