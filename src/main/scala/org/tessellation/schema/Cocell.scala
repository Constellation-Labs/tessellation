package org.tessellation.schema

import cats.effect.Concurrent
import cats.free.Cofree
import cats.Eval

case class CoCellT[F[_] : Concurrent, A](value: A, stateTransitionEval: Eval[Cell[Cofree[Cell, A]]])

case class Cocell[A](value: A, stateTransitionEval: Eval[Cell[Cofree[Cell, A]]]) extends Hom[A]{
  val plan: Cofree[Cell, A] = Cofree[Cell, A](value, stateTransitionEval)
}
