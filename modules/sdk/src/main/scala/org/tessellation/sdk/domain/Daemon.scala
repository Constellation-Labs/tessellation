package org.tessellation.sdk.domain

import cats.effect.kernel.Async
import cats.effect.std.Supervisor
import cats.syntax.functor._

trait Daemon[F[_]] {
  def start: F[Unit]
}

object Daemon {

  def spawn[F[_]: Async](thunk: F[Unit])(implicit S: Supervisor[F]): Daemon[F] = new Daemon[F] {
    def start: F[Unit] = S.supervise(thunk).void
  }

}
