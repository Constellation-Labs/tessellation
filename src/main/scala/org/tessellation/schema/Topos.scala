package org.tessellation.schema

import cats.arrow.{Arrow, FunctionK}
import cats.free.{Coyoneda, Free}
import cats.{Functor, MonoidK, Representable, ~>}
import higherkindness.droste.data.{:<, Mu, Nu}
import higherkindness.droste._
import org.tessellation.schema.Topos.{Enriched}
import cats.implicits._

/**
 * Topos context
 */
trait Topos[A, B] extends Hom[A, B] {
  val terminator: Ω = this // subobject classifier
  val identity = natTrans
  def pow: Ω => Ω = _ => this // finite limits should exist
  def natTrans: ~>[Enriched, Enriched] = λ[Enriched ~> Enriched](fa => fa)
  val freeTransform = Topos.inject(natTrans)
}

object Topos {
  type FreeF[S[_], A] = Free[Coyoneda[S, ?], A]
  type Enriched[A] = FreeF[Topos[?, A], A]

  implicit val arrow = new Arrow[Topos] {
    override def lift[A, B](f: A => B): Topos[A, B] = ???

    // todo lift into Day and return new Topos
    override def compose[A, B, C](f: Topos[B, C], g: Topos[A, B]): Topos[A, C] = ???

    override def first[A, B, C](fa: Topos[A, B]): Topos[(A, C), (B, C)] = ???
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

  implicit def monoidK[A]: MonoidK[Enriched] = new MonoidK[Enriched] {
    override def empty[A]: Enriched[A] = ???

    override def combineK[A](x: Enriched[A], y: Enriched[A]): Enriched[A] = ???
  }
}

object Channel {
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

  implicit def rFunctor[A]: Functor[Hom[A, *]] = new Functor[Hom[A, *]] {
    override def map[B, C](fa: Hom[A, B])(f: B => C): Hom[A, C] =
      fa match {
        case Context() => Context()
        case TwoCell(a, b) => TwoCell(a, f(b))
      }
  }

  def natCvAlgebra[A]: CVAlgebra[Hom[A, *], Int] = CVAlgebra {
    case Context() => 0
    case Cell(_) => 1
    case TwoCell(_, _ :< Cell(_)) => 1
    case TwoCell(_, t :< TwoCell(_, _)) => 1 + t
  }

  def sizeM[A](ll: Channel[A]): Int =
    scheme.zoo.dyna[Hom[A, *], ll.A, Int](natCvAlgebra[A], ll.unfold).apply(ll.a)

}