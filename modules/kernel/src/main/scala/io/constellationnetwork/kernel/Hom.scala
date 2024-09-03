package io.constellationnetwork.kernel

import cats.kernel.PartialOrder

/** Characteristic Sheaf just needs to be Poset
  */
trait Poset extends PartialOrder[Ω] {
  override def partialCompare(x: Ω, y: Ω): Double = if (x == y) 0.0 else 1.0
}

/** Terminal object
  */
trait Ω extends Poset

object Ω {
  implicit def toΩList(a: Ω): ΩList =
    a match {
      case xs: ΩList => xs
      case otherOhm  => ::(otherOhm, ΩNil)
    }
}

/** Homomorphism object for determining morphism isomorphism
  */
trait Hom[+A, +B] extends Ω {}
