package org.tessellation.schema
import cats.arrow.Arrow
import cats.{Functor, Representable}
import cats.implicits._
import cats.kernel.Group
import org.tessellation.schema.Topos.Contravariant

/**
* Preserves Trace
  * @tparam A
  * @tparam B
  */
abstract class Abelian[A, B](data: A) extends Group[A]

/**
* Preserves Braiding
  * @tparam A
  * @tparam B
  */
abstract class Frobenius[A, B](data: A) extends Abelian[A, B](data)
