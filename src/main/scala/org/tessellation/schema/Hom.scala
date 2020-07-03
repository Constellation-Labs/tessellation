package org.tessellation.schema

import cats.effect.Concurrent
import cats.free.FreeT
import cats.syntax.applicative._
import cats.syntax.functor._
import cats.{Applicative, Bimonad, Eq, Eval, Monoid, MonoidK, Traverse, ~>}
import higherkindness.droste.util.DefaultTraverse
import higherkindness.droste.{Algebra, AlgebraM, Basis, Coalgebra, CoalgebraM, Delay, Embed, Project, scheme, syntax, _}
import org.tessellation.schema.Hom.{Operad, _}

sealed trait Hom[A, B] {

  def set[B >: A](x: B): Operad[B] = Hom(x)

  def opM(algebra: AlgebraM[Operad, Operad, A],
                          coalgebra: CoalgebraM[Operad, Operad, B]): B => Operad[A] =
    scheme.hyloM(algebra, coalgebra)(biMonad, traverse)

  def op(algebra: Algebra[Operad, A] = algebra,
                         coalgebra: Coalgebra[Operad, B] = coalgebra): B => A = scheme.hylo(algebra, coalgebra)(traverse)

  def opT(data: B): FreeT[Operad, Operad, A] = FreeT.liftF[Operad, Operad, A](Hom(op(algebra, coalgebra)(data)))(applicative)

  def algebraM: AlgebraM[Operad, Operad, A] = AlgebraM[Operad, Operad, A] {
    case (o: Operad[A]) => o
  }

  def coalgebraM: CoalgebraM[Operad, Operad, B] = CoalgebraM[Operad, Operad, B] {
    case c: B => Hom(Hom(c))
  }

  def algebra: Algebra[Operad, A] = Algebra[Operad, A] {
    case (o: Operad[A]) => biMonad.extract(o)
  }

  def coalgebra: Coalgebra[Operad, B] = Coalgebra[Operad, B] {
    case b: B => Hom(b)
  }

  /** //todo def for symbol |~>
   * Composition preserving lift as specified by https://ncatlab.org/nlab/show/Boardman-Vogt+tensor+product
   *
   * @return Cofree result of matching type, override for new Cell/Cocell types
   */
  def tensor: Operad ~> Operad = new (Operad ~> Operad) {

    import org.tessellation.schema.Hom.Operad

    def apply[A](fa: Operad[A]): Operad[A] = {
      fa match {
        case o: Operad[A] => o
      }
    }
  }

  def unit: Hom[A, B] = this
}

abstract class Fiber[A, B] extends Hom[A, B]

abstract class Bundle[F, G](fibers: F) extends Fiber[F, G]

abstract class Simplex[T, U, V](fibers: Seq[Hom[T, U]]) extends Hom[U, V]

case class CellT[F[_] : Concurrent, A](value: A) {}

case class Cell[A, B](value: A) extends Hom[A, B]

case class Cocell[A, B](value: A, stateTransitionEval: B) extends Hom[A, B]

case class Context[A, B]() extends Hom[A, B]

object Hom {
  type Operad[T] = Hom[T, T]

  def apply[T](t: T): Operad[T] = new Hom[T, T] {}

  implicit val applicative = new Applicative[Operad] {
    override def pure[A](x: A): Operad[A] = ???

    override def ap[A, B](ff: Operad[A => B])(fa: Operad[A]): Operad[B] = ???
  }

  implicit val monoidK = new MonoidK[Operad] {
    override def empty[A]: Operad[A] = new Hom[A, A] {}

    override def combineK[A](x: Operad[A], y: Operad[A]): Operad[A] = {
      val metaAlgebra: Algebra[Operad, A] = x.algebra.compose(y.algebra)(biMonad)
      val metaCoalgebra: Coalgebra[Operad, A] = x.coalgebra.compose(y.coalgebra)(biMonad)
      new Hom[A, A] {
        override def algebra: Algebra[Operad, A] = metaAlgebra

        override def coalgebra: Coalgebra[Operad, A] = metaCoalgebra
      }
    }
  }

  implicit val traverse = new Traverse[Operad] {
    override def traverse[G[_], A, B](fa: Operad[A])(f: A => G[B])(implicit evidence$1: Applicative[G]): G[Operad[B]] = ???

    override def foldLeft[A, B](fa: Operad[A], b: B)(f: (B, A) => B): B = ???

    override def foldRight[A, B](fa: Operad[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] = ???
  }

  implicit val biMonad = new Bimonad[Operad] {
    override def pure[A](x: A): Operad[A] = ???

    override def flatMap[A, B](fa: Operad[A])(f: A => Operad[B]): Operad[B] = ???

    override def tailRecM[A, B](a: A)(f: A => Operad[Either[A, B]]): Operad[B] = ???

    override def extract[A](x: Operad[A]): A = ???

    override def coflatMap[A, B](fa: Operad[A])(f: Operad[A] => B): Operad[B] = ???
  }

  implicit def drosteTraverseForHom[A]: Traverse[Hom[A, *]] =
    new DefaultTraverse[Hom[A, *]] {
      def traverse[F[_] : Applicative, B, C](fb: Hom[A, B])(f: B => F[C]): F[Hom[A, C]] =
        fb match {
          case Cocell(head, tail) => f(tail).map(Cocell(head, _))
          case Cell(value) => (Cell(value): Hom[A, C]).pure[F]
          case Context() => (Context(): Hom[A, C]).pure[F]
        }
    }

  def toScalaList[A, PatR[_[_]]](list: PatR[Hom[A, *]])(
    implicit ev: Project[Hom[A, *], PatR[Hom[A, *]]]
  ): List[A] =
    scheme.cata(toScalaListAlgebra[A]).apply(list)

  def toScalaListAlgebra[A]: Algebra[Hom[A, *], List[A]] = Algebra {
    case Cocell(head, tail) => head :: tail
    case Cell(thing) => thing :: Nil
    case Context() => Nil
  }

  def fromScalaList[A, PatR[_[_]]](list: List[A])(
    implicit ev: Embed[Hom[A, *], PatR[Hom[A, *]]]
  ): PatR[Hom[A, *]] =
    scheme.ana(fromScalaListCoalgebra[A]).apply(list)

  def fromScalaListCoalgebra[A]: Coalgebra[Hom[A, *], List[A]] = Coalgebra {
    case head :: Nil => Cell(head)
    case head :: tail => Cocell(head, tail)
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

  import cats.~>
  import syntax.compose._

  implicit def drosteDelayEqHom[A](implicit eh: Eq[A]): Delay[Eq, Hom[A, *]] =
    λ[Eq ~> (Eq ∘ Hom[A, *])#λ](et =>
      Eq.instance((x, y) =>
        x match {
          case Cocell(hx, tx) =>
            y match {
              case Cocell(hy, ty) => eh.eqv(hx, hy) && et.eqv(tx, ty)
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
}
