package org.tessellation

import cats.{Functor, ~>}
import fs2.Stream

package object utils {

  def streamLiftK[F[_]](implicit F: Functor[F]): F ~> Stream[F, *] = new (F ~> Stream[F, *]) {
    override def apply[A](fa: F[A]): Stream[F, A] = Stream.eval(fa)
  }
}
