package org.tessellation.schema

import cats.arrow.{Arrow, ArrowChoice, CommutativeArrow}
import cats.free.{Coyoneda, Free}
import cats.syntax.all._
import cats.{Functor, Representable, ~>}
import higherkindness.droste._
import higherkindness.droste.data.{Mu, Nu}

/**
 * Topos context for morphisms of morphisms
 */
trait Topos[A, B] extends Hom[A, B] {
  import Hom._

  val arrow = Hom.arrowInstance
  val a: A
  val terminator: Ω = this // subobject classifier
  val identity = natTrans

  def pow: Ω => Ω = _ => this // finite limits should exist https://ncatlab.org/nlab/show/exponentiable+topos
  // todo mock for lookups of natTrans: F ~> F for _pro morphisms
  def natTrans: ~>[Topos[A, *], Topos[A, *]] = λ[Topos[A, *] ~> Topos[A, *]](fa => fa)

  val gather = cvAlgebra.gather(Gather.histo)
  val scatter = rcoalgebra.scatter(Scatter.gapo(coalgebra))
  val run: Ω => Ω = scheme.ghylo(gather, scatter)
}

object Topos {
  type FreeF[S[_], A] = Free[Coyoneda[S, ?], A]

  def combine[F[_, _] : Arrow, A, B, C](fab: F[A, B],
                                        fac: F[A, C]): F[A, (B, C)] =
    Arrow[F].lift((a: A) => (a, a)) >>> (fab *** fac)

  def combineImplicit[F[_, _] : Arrow, A, B, C](fab: F[A, B], fac: F[A, C]): F[A, (B, C)] = {
    val fa = implicitly[Arrow[F]]
    fa.lmap[(A, A), (B, C), A](fa.split[A, B, A, C](fab, fac))(a => (a, a))
  }

  /**
   * similar to the combine function with the addition of running a function on the result of combine
   *
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
  def liftA2[F[_, _] : Arrow, A, B, C, D](fab: F[A, B], fac: F[A, C])(f: B => C => D): F[A, D] = {
    val fa = implicitly[Arrow[F]]
    combine[F, A, B, C](fab, fac).rmap { case (b, c) => f(b)(c) }
  }

  /**
   * FunctionK but with a CoYoneda decomposition.
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

  //todo this should be on hom
  implicit val repr = new Representable[Topos[Ω, *]] {
    override def F: Functor[Topos[Ω, *]] = ???

    override type Representation = this.type

    override def index[A](f: Topos[Ω, A]): this.type => A = ???

    // https://ncatlab.org/nlab/show/2-sheaf
    // https://ncatlab.org/nlab/show/indexed+category

    /**
     * todo use Enrichment to maintain order
     *
     * @param f
     * @tparam A
     * @return
     */
    override def tabulate[A](f: this.type => A): Topos[Ω, A] = ???
  }

  //left exact right derived
  val representation: Representable[Topos[Ω, *]] = Representable(repr)
}
