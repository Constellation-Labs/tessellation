package org.tessellation.schema

import cats.arrow.{Arrow, Category}
import cats.free.{Coyoneda, Free}
import cats.implicits._
import cats.kernel.Monoid
import cats.{
  Applicative,
  Bifunctor,
  Eq,
  Functor,
  MonoidK,
  Representable,
  Traverse,
  ~>
}
import higherkindness.droste._
import higherkindness.droste.data.Coattr
import higherkindness.droste.syntax.compose._
import higherkindness.droste.util.DefaultTraverse

/**
  * Terminal object
  */
sealed trait Ω

/**
  * Homomorphism object for determining morphism isomorphism
  */
sealed trait Hom[+A, +B] extends Ω

case class CoCell[A, B](run: A => (CoCell[A, B], B)) extends Hom[A, B]

case class Cell[A, B](data: A) extends Hom[A, B]

case class TwoCell[A, B](data: A, stateTransitionEval: B) extends Hom[A, B]

case class Context() extends Hom[Nothing, Nothing]

object Hom {
  import cats.syntax.applicative._
  import cats.syntax.functor._

  def combine[F[_, _]: Arrow, A, B, C](fab: F[A, B],
                                       fac: F[A, C]): F[A, (B, C)] =
    Arrow[F].lift((a: A) => (a, a)) >>> (fab *** fac)

  def runList[A, B](ff: CoCell[A, B], as: List[A]): List[B] = as match {
    case h :: t =>
      val (ff2, b) = ff.run(h)
      b :: runList(ff2, t)
    case _ => List()
  }

  implicit val arrowInstance: Arrow[CoCell] = new Arrow[CoCell] {

    override def lift[A, B](f: A => B): CoCell[A, B] = CoCell(lift(f) -> f(_))

    override def first[A, B, C](fa: CoCell[A, B]): CoCell[(A, C), (B, C)] =
      CoCell {
        case (a, c) =>
          val (fa2, b) = fa.run(a)
          (first(fa2), (b, c))
      }

    override def compose[A, B, C](f: CoCell[B, C],
                                  g: CoCell[A, B]): CoCell[A, C] = CoCell { a =>
      val (gg, b) = g.run(a)
      val (ff, c) = f.run(b)
      (compose(ff, gg), c)
    }
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

  def accum[A, B](b: B)(f: (A, B) => B): CoCell[A, B] = CoCell { a =>
    val b2 = f(a, b)
    (accum(b2)(f), b2)
  }

  def sum[A: Monoid]: CoCell[A, A] = accum(Monoid[A].empty)(_ |+| _)
  def count[A]: CoCell[A, Int] = Arrow[CoCell].lift((_: A) => 1) >>> sum
  def avg: CoCell[Int, Double] =
    combine(sum[Int], count[Int]) >>> Arrow[CoCell].lift {
      case (x, y) => x.toDouble / y
    }
}

/**
  * Topos context
  */
trait Topos[C[_, _]] extends Category[C] { //todo use lambdas A <-> O here
  // finite limits should exist
  def tensor(x: this.type, y: this.type): this.type
  // subobject classifier
  val Ω: this.type
  def pow: this.type => this.type
}

object Topos {
  type FreeF[S[_], A] = Free[Coyoneda[S, *], A]
  type Enriched[A] = Coattr[Hom[?, A], A]

  implicit val rep = new Representable[Enriched] {
    override def F: Functor[Enriched] = ???

    override type Representation = this.type

    override def index[A](f: Enriched[A]): this.type => A = ???
    // https://ncatlab.org/nlab/show/2-sheaf
    // https://ncatlab.org/nlab/show/indexed+category

    override def tabulate[A](f: this.type => A): Enriched[A] = ???
  }

  def inject[F[_], G[_]](transformation: F ~> G) =
    new (FreeF[F, *] ~> FreeF[G, *]) { //transformation of free algebras
      def apply[A](fa: FreeF[F, A]): FreeF[G, A] =
        fa.mapK[Coyoneda[G, *]](new (Coyoneda[F, *] ~> Coyoneda[G, *]) {
          def apply[B](fb: Coyoneda[F, B]): Coyoneda[G, B] =
            fb.mapK(transformation)
        })
    }

  implicit val monoidK = new MonoidK[Enriched] {
    override def empty[A]: Enriched[A] = ???

    override def combineK[A](x: Enriched[A], y: Enriched[A]): Enriched[A] = ???
  }
}

abstract class Fiber[A, B]

abstract class Bundle[F, G](fibers: F)

abstract class Simplex[T, U, V](fibers: Seq[Hom[T, U]])
