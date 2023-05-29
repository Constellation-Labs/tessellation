package org.tessellation.fsm

import cats.syntax.all._
import cats.{Functor, Id}

case class FSM[F[_], S, I, O](run: (S, I) => F[(S, O)]) {
  def runS(implicit F: Functor[F]): (S, I) => F[S] =
    (s, i) => run(s, i).map(_._1)
}

object FMS {
  def id[S, I, O](run: (S, I) => Id[(S, O)]) = FSM(run)
}
