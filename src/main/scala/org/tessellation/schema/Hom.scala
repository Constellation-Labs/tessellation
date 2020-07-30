package org.tessellation.schema

import cats.arrow.Arrow
import cats.free.{Coyoneda, Free}
import cats.implicits._
import cats.kernel.{Monoid, PartialOrder}
import cats.{Applicative, Bifunctor, Eq, Functor, MonoidK, Representable, Traverse, ~>}
import higherkindness.droste.data.{Fix, Mu}
import higherkindness.droste.syntax.compose._
import higherkindness.droste.util.DefaultTraverse
import higherkindness.droste.{Algebra, _}
import org.tessellation.schema.Cocell.accum
import org.tessellation.schema.Topos.Enriched

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

  implicit val bifunctor: Bifunctor[Hom] = new Bifunctor[Hom] {
    override def bimap[A, B, C, D](fab: Hom[A, B])(f: A => C,
                                                   g: B => D): Hom[C, D] = ???
  }

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

  // todo this is where we use Poset to order events
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
import higherkindness.droste.data.Nu

/**
  * Category of groups https://github.com/higherkindness/droste/blob/master/modules/tests/src/test/scala/higherkindness/droste/tests/SchemeEquivalence.scala
  *
  * @tparam A
  * @tparam B
  */
trait Group[A, B] extends Hom[A, B] {
  import Group._
  import higherkindness.droste._
  val data: A
//  def action = scheme.hylo(this.algebra, this.coalgebra)(repr.F)
//  def coaction = scheme.cata(this.algebra)
}


object Group {
  type Top[C] = Mu[Hom[C, *]]
  type Man[C] = Nu[Hom[*, C]]

  implicit val repr: Representable[Top] = new Representable[Top] {
    override def F
    : Functor[Top] = ???
    override type Representation = this.type
    override def index[A](
      f: Top[A]
    ): this.type => A = ???
    override def tabulate[A](
      f: this.type => A
    ): Top[A] = ???
  }

  implicit val rightDerivedF: Functor[Top] = repr.F

  implicit class GroupBasis[A](data: A) extends Embed[Top, A] with Project[Top, A]{
    override def coalgebra: Coalgebra[
      Top,
    A
  ] = ???
    override def algebra: Algebra[
      Top,
      A
    ] = ???
  }
}

case class Cell[A, B](data: A) extends Group[A, B]

case class TwoCell[A, B](data: A, stateTransitionEval: B) extends Group[A, B]

case class Context() extends Hom[Nothing, Nothing]

case class Cocell[A, B](run: A => (Cocell[A, B], B)) extends Hom[A, B]

object Cocell {
  type CofreeCocell[A] = Cocell[A, _]

  implicit val monoidK = new MonoidK[CofreeCocell] {
    override def empty[A]
      : _root_.org.tessellation.schema.Cocell.CofreeCocell[A] =
      ???
    override def combineK[A](
      x: _root_.org.tessellation.schema.Cocell.CofreeCocell[A],
      y: _root_.org.tessellation.schema.Cocell.CofreeCocell[A]
    ): _root_.org.tessellation.schema.Cocell.CofreeCocell[A] = ???
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
}

/**
  * Topos context
  */
trait Topos[G[_, _] <: Group[_, _]] extends Arrow[G] with Ω {
  val terminator: Ω = this // subobject classifier
  def pow: Ω => Ω = _ => this
  // finite limits should exist
  implicit val repr = new Representable[Enriched] {
    override def F: Functor[Enriched] = ???

    override type Representation = this.type

    override def index[A](f: Enriched[A]): this.type => A = ???
    // https://ncatlab.org/nlab/show/2-sheaf
    // https://ncatlab.org/nlab/show/indexed+category

    /**
      * todo use Enrichment to maintain order
      *
      * @param f
      * @tparam A
      * @return
      */
    override def tabulate[A](f: this.type => A): Enriched[A] = ???
  }

  val representation: Representable[Enriched] = Representable(repr)
  override def lift[A, B](f: A => B): G[A, B] = ???
  override def first[A, B, C](fa: G[A, B]): G[(A, C), (B, C)] = ???
  override def compose[A, B, C](f: G[B, C], g: G[A, B]): G[A, C] = ???
}

object Topos {
  type FreeF[S[_], A] = Free[Coyoneda[S, ?], A]
  type Enriched[A] = FreeF[Hom[?, A], A]

  //todo map between recursion schemes and Cofree, define morphisms and colimits with lambdas below ->
  //  val coAttr = Coattr.fromCats()
  //  val listToOption = λ[FunctionK[List, Option]](_.headOption)

  def combine[F[_, _]: Arrow, A, B, C](fab: F[A, B],
                                       fac: F[A, C]): F[A, (B, C)] =
    Arrow[F].lift((a: A) => (a, a)) >>> (fab *** fac)

  def combineImplicit[F[_, _]: Arrow, A, B, C](fab: F[A, B], fac: F[A, C]): F[A, (B, C)] = {
    val fa = implicitly[Arrow[F]]
    fa.lmap[(A, A), (B, C), A](fa.split[A, B, A, C](fab, fac))(a => (a, a))
  }

  /**
    * similar to the combine function with the addition of running a function on the result of combine
    * @param fab
    * @param fac
    * @param f
    * @tparam F
    * @tparam A
    * @tparam B
    * @tparam C
    * @tparam D
    * @return
    */
  def liftA2[F[_, _]: Arrow, A, B, C, D](fab: F[A, B], fac: F[A, C])(f: B => C => D): F[A, D] = {
    val fa = implicitly[Arrow[F]]
    combine[F, A, B, C](fab, fac).rmap { case (b, c) => f(b)(c) }
  }

  /**
    * FunctionK but with a CoYoneda decomposition
    *
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
  implicit val monoidK = new MonoidK[Enriched] {
    override def empty[A]: _root_.org.tessellation.schema.Topos.Enriched[A] =
      ???
    override def combineK[A](
      x: _root_.org.tessellation.schema.Topos.Enriched[A],
      y: _root_.org.tessellation.schema.Topos.Enriched[A]
    ): _root_.org.tessellation.schema.Topos.Enriched[A] = ???
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
