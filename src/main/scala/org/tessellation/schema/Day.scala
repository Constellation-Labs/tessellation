package org.tessellation.schema

import cats.MonoidK

/**
 * CoTopos or monad transformer for Topos
 * @tparam F
 * @tparam G
 * @tparam A
 */
sealed trait Day[F[_], G[_], A] extends Hom[F[A], G[A]]{
  type B
  type C

  val f: F[B]
  val g: G[C]
  val a: (B, C) => A
}

object Day {
  def apply[F[_], B0, G[_], C0, A](f0: F[B0], g0: G[C0], a0: (B0, C0) => A): Day[F, G, A] =
    new Day[F, G, A] {
      override type B = B0
      override type C = C0

      val f: F[B0] = f0
      val g: G[C0] = g0
      val a: (B0, C0) => A = a0
    }

  def unapply[F[_], B0, G[_], C0, A](d: Day[F, G, A]): Option[(F[d.B], G[d.C], (d.B, d.C) => A)] =
    Some(d.f, d.g, d.a)

  /**
   * Shape preserving execution algebra for Topos Representable
   * @param d
   * @tparam A
   * @return
   */
  def takeNext[A](d: Day[Option, (A, *), A]): A =
    d match {
      case Day(None, (h: A, _), _) => h
      case Day(Some(x: d.B), (_, t: d.C), f: ((d.B, d.C) => A)) => f(x, t)
    }

  /**
   * Lattice bounds for bounded lattice given by coposition of Topos. Morphisms of two different comonads are therefore
   * composed by precomposing the morphisms with comonad morphisms from the least-upper bound comonad
   * @tparam F
   * @tparam B
   * @tparam G
   * @tparam C
   * @return
   */
  implicit def dayMonoidK[F[_], B, G[_], C]: MonoidK[Day[F, G, *]] = new MonoidK[Day[F, G, *]] {
    override def empty[A]: Day[F, G, A] = ???

    override def combineK[A](x: Day[F, G, A], y: Day[F, G, A]): Day[F, G, A] = ???
  }
}

