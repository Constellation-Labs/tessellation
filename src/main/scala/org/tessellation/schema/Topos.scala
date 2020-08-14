package org.tessellation.schema

import cats.arrow.{Arrow, FunctionK}
import cats.free.{Coyoneda, Free}
import cats.{Functor, MonoidK, Representable, ~>}
import higherkindness.droste.data.{:<, Mu, Nu}
import higherkindness.droste._
import org.tessellation.schema.Topos.{Contravariant, Covariant}
import cats.implicits._

/**
 * Topos context for morphisms of morphisms
 */
trait Topos[A, B] extends Hom[A, B] {
  val run: A => (Topos[A, B], B)
  val terminator: Ω = this // subobject classifier
  val identity = natTrans
  def pow: Ω => Ω = _ => this // finite limits should exist
  def natTrans: ~>[Covariant, Covariant] = λ[Covariant ~> Covariant](fa => fa)
  val freeTransform = Topos.inject(natTrans)
  implicit val rFunctor: Functor[Hom[A, *]] = Topos.rFunctor[A]
}

object Topos {
  type FreeF[S[_], A] = Free[Coyoneda[S, ?], A]
  type Contravariant[A] = FreeF[Topos[?, A], A]
  type Covariant[A] = FreeF[Topos[A, ?], A]

  implicit val arrowInstance: Arrow[Topos] = new Arrow[Topos] {

    override def lift[A, B](f: A => B): Topos[A, B] = new Topos[A, B] {
      val run = lift(f) -> f(_)
    }

    override def first[A, B, C](fa: Topos[A, B]): Topos[(A, C), (B, C)] =
      new Topos[(A, C), (B, C)] {
        val run = {
          case (a, c) =>
            val (fa2, b) = fa.run(a)
            (first(fa2), (b, c))
        }
      }

    override def compose[A, B, C](f: Topos[B, C],
                                  g: Topos[A, B]): Topos[A, C] = new Topos[A, C] {
      def morph(a: A) = {
        val (gg, b) = g.run(a)
        val (ff, c) = f.run(b)
        (compose(ff, gg), c)
      }
      val run = morph(_)
    }
  }

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
   * FunctionK but with a CoYoneda decomposition. todo use this and reduce over Day as lFunctor the resolves nat transforms
   *
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

  implicit val repr = new Representable[Contravariant] {
    override def F: Functor[Contravariant] = ???

    override type Representation = this.type

    override def index[A](f: Contravariant[A]): this.type => A = ???
    // https://ncatlab.org/nlab/show/2-sheaf
    // https://ncatlab.org/nlab/show/indexed+category

    /**
     * todo use Enrichment to maintain order
     *
     * @param f
     * @tparam A
     * @return
     */
    override def tabulate[A](f: this.type => A): Contravariant[A] = ???
  }

  val representation: Representable[Contravariant] = Representable(repr)

  implicit def monoidK[A]: MonoidK[Contravariant] = new MonoidK[Contravariant] {
    override def empty[A]: Contravariant[A] = ???

    override def combineK[A](x: Contravariant[A], y: Contravariant[A]): Contravariant[A] = ???
  }

  implicit def rFunctor[A]: Functor[Hom[A, *]] = new Functor[Hom[A, *]] {
    override def map[B, C](fa: Hom[A, B])(f: B => C): Hom[A, C] =
      fa match {
        case Context() => Context()
        case Cell2(a, b) => Cell2(a, f(b))
      }
  }
}

abstract class Channel[A, B](data: A) extends Topos[A, B] {
  import Topos.arrowInstance
//  val consensus: Hom[A, B] = Cell(data)
//  val convergeSnapshots: Hom[B, Context.type] = Cell(consensus.run)
//  val pipeline = consensus |+| convergeSnapshots
}

object Channel {
  import Topos.rFunctor
  type Channel[A] = Nu[Hom[A, *]]
  type Cochain[A] = Mu[Hom[A, *]]

  implicit class ChannelBasis[A](data: A) extends Embed[Channel, A] with Project[Cochain, A]{
    override def coalgebra: Coalgebra[
      Cochain,
      A
    ] = ???
    override def algebra: Algebra[
      Channel,
      A
    ] = ???
  }

  def natCvAlgebra[A]: CVAlgebra[Hom[A, *], Int] = CVAlgebra {
    case Context() => 0
    case Cell(_) => 1
    case Cell2(_, _ :< Cell(_)) => 1
    case Cell2(_, t :< Cell2(_, _)) => 1 + t
  }

  def sizeM[A](ll: Channel[A]): Int =
    scheme.zoo.dyna[Hom[A, *], ll.A, Int](natCvAlgebra[A], ll.unfold).apply(ll.a)
}