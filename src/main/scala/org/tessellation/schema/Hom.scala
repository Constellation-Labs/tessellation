package org.tessellation.schema

import cats.arrow.{Profunctor, Strong}
import cats.effect.Concurrent
import cats.free.{Coyoneda, Free, FreeT}
import cats.syntax.applicative._
import cats.syntax.functor._
import cats.{Applicative, Bimonad, Eq, Eval, Functor, Monoid, MonoidK, Representable, Traverse, ~>}
import higherkindness.droste.syntax.compose._
import higherkindness.droste.util.DefaultTraverse
import higherkindness.droste._
import org.tessellation.schema.Hom.{Operad, _}

sealed abstract class Hom[A, B] {

  def set[C >: A](x: C): Operad[C] = Hom(x)

  def opM(algebra: AlgebraM[Operad, Operad, A] = algebraM,
          coalgebra: CoalgebraM[Operad, Operad, B] = coalgebraM): B => Operad[A] =
    scheme.hyloM(algebra, coalgebra)(biMonad, traverse)

  def op(algebra: Algebra[Operad, A] = algebra,
         coalgebra: Coalgebra[Operad, B] = coalgebra): B => A = scheme.hylo(algebra, coalgebra)(traverse)

  def opT(data: B): FreeT[Operad, Operad, A] = FreeT.liftF[Operad, Operad, A](Hom(op(algebra, coalgebra)(data)))(biMonad)

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

  def natTrans: Operad[A] => Operad[B] = _ => Hom[B]

  /**
   * Composition preserving lift as specified by https://ncatlab.org/nlab/show/Boardman-Vogt+tensor+product. This also
   * serves as the composition operator in relation to colored Operad laws.
   *
   * @return Cofree result of matching type, override for new Cell/Cocell types
   */
  def chain: Operad ~> Operad = new (Operad ~> Operad) {
    def apply[C](fa: Operad[C]): Operad[C] = {
      fa match {
        case o: Operad[C] => o natTrans o
      }
    }
  }

  def chained(data: B): FreeT[Operad, Operad, A] = opT(data).hoist(chain)

  def |~>: : B => FreeT[Operad, Operad, A] = chained _

  def unit: Hom[A, B] = this
}

abstract class Fiber[A, B] extends Hom[A, B]

abstract class Bundle[F, G](fibers: F) extends Fiber[F, G]

abstract class Simplex[T, U, V](fibers: Seq[Hom[T, U]]) extends Operad[T] // todo fold to new obj

case class CellT[F[_] : Concurrent, A](value: A) {}

case class Cocell[A, B](val data: A, stateTransitionEval: B) extends Hom[A, B]

case class Cell[A, B](val data: A) extends Hom[A, B]

case class Context[A, B]() extends Hom[A, B]

object Hom {
    /**
     * In a braided symmetric monoidal category, operations map Hom[A, B] where A = B
     *
     * @tparam T
     */
  type Operad[T] = Hom[T, T]

  type FreeF[S[_], A] = Free[({type f[x] = Coyoneda[S, x]})#f, A]

  implicit val hom = new Strong[Hom] {
    override def first[A, B, C](fa: Hom[A, B]): Hom[(A, C), (B, C)] = ???

    override def second[A, B, C](fa: Hom[A, B]): Hom[(C, A), (C, B)] = ???

    override def dimap[A, B, C, D](fab: Hom[A, B])(f: C => A)(g: B => D): Hom[C, D] = ???
  }

  implicit val rep = new Representable[Operad] {
    override def F: Functor[Operad] = ???

    override type Representation = this.type

    override def index[A](f: Operad[A]): this.type => A = ???
    // https://ncatlab.org/nlab/show/2-sheaf
    // https://ncatlab.org/nlab/show/indexed+category

    override def tabulate[A](f: this.type => A): Operad[A] = ???
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

  def apply[T](t: T): Operad[T] = new Hom[T, T] {}

  def apply[T]: Operad[T] = new Hom[T, T] {}

  implicit val monoidK = new MonoidK[Operad] {
    override def empty[A]: Operad[A] = new Hom[A, A] {}

    /**
     * Sufficient for endomorphism monoid https://ncatlab.org/nlab/show/endomorphism. Provides commutativity via ring
     * structure todo follow RalgebraM and .zip for metaAlgebra?
     * @param x
     * @param y
     * @tparam A
     * @return
     */
    override def combineK[A](x: Operad[A], y: Operad[A]): Operad[A] = {
      val metaAlgebra: Algebra[Operad, A] = x.algebra.compose(y .algebra)(biMonad)
      val metaCoalgebra: Coalgebra[Operad, A] = x.coalgebra.compose(y.coalgebra)(biMonad)
      new Hom[A, A] {
        override def algebra: Algebra[Operad, A] = metaAlgebra

        override def coalgebra: Coalgebra[Operad, A] = metaCoalgebra
      }
    }
  }

  implicit val traverse = new Traverse[Operad] {
    override def traverse[G[_], A, B](fa: Operad[A])(f: A => G[B])(implicit evidence$1: Applicative[G]): G[Operad[B]] = ???
//      f(fa.op()(fa.data)).map(Hom(_))

    /**
     * todo f should be |~> chains, just make object with implicit defs like shapeless
     * @param fa
     * @param b
     * @param f
     * @tparam A
     * @tparam B
     * @return
     */
    override def foldLeft[A, B](fa: Operad[A], b: B)(f: (B, A) => B): B = ???

    /**
     * We'll probably want to wrap op in State, then pass Evals between ops. Can probably chain CellT with λ
     * @param fa
     * @param lb
     * @param f
     * @tparam A
     * @tparam B
     * @return
     */
    override def foldRight[A, B](fa: Operad[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] = ???
  }

  implicit val biMonad = new Bimonad[Operad] {
    override def pure[A](x: A): Operad[A] = Hom(x)

    override def flatMap[A, B](fa: Operad[A])(f: A => Operad[B]): Operad[B] = ???
      //f(fa.op()(fa.data))

    override def tailRecM[A, B](a: A)(f: A => Operad[Either[A, B]]): Operad[B] = ???

    override def extract[A](x: Operad[A]): A = ???
      //x.data

    override def coflatMap[A, B](fa: Operad[A])(f: Operad[A] => B): Operad[B] = Hom(f(fa))
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
