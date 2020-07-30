package org.tessellation.schema

abstract class DAG[A, B](data: A) extends Topos[Frobenius]

/**
* Preserves Trace
  * @tparam A
  * @tparam B
  */
abstract class Abelian[A, B](data: A) extends Group[A, B]

/**
* Preserves Braiding
  * @tparam A
  * @tparam B
  */
abstract class Frobenius[A, B](data: A) extends Abelian[A, B](data)
