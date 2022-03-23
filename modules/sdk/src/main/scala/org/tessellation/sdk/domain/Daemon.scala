package org.tessellation.sdk.domain

import cats.effect.Spawn
import cats.syntax.functor._

trait Daemon[F[_]] {
  def start: F[Unit]
}

object Daemon {

  def spawn[F[_]: Spawn](thunk: F[Unit]): Daemon[F] = new Daemon[F] {
    def start: F[Unit] = Spawn[F].start(thunk).void
  }

}
