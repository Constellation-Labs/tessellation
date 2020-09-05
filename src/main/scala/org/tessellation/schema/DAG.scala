package org.tessellation.schema

import cats.kernel.Group

/**
 * Preserves Trace
 *
 * @tparam A
 * @tparam B
 */
abstract class Abelian[A, B](data: A) extends Group[A]

/**
 * Preserves Braiding
 *
 * @tparam A
 * @tparam B
 */
abstract class Frobenius[A, B](data: A) extends Abelian[A, B](data)
