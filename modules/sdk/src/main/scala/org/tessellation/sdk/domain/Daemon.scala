package org.tessellation.sdk.domain

import cats.effect.kernel.Async
import cats.effect.std.Supervisor
import cats.syntax.applicativeError._
import cats.syntax.functor._

import scala.concurrent.duration.FiniteDuration

import fs2.Stream
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait Daemon[F[_]] {
  def start: F[Unit]
}

object Daemon {

  def spawn[F[_]: Async](thunk: F[Unit])(implicit S: Supervisor[F]): Daemon[F] = new Daemon[F] {
    def start: F[Unit] = S.supervise(thunk).void
  }

  def periodic[F[_]: Async](thunk: F[Unit], sleepTime: FiniteDuration)(implicit S: Supervisor[F]): Daemon[F] =
    new Daemon[F] {
      private val daemonLogger = Slf4jLogger.getLoggerFromClass[F](Daemon.getClass)

      def start: F[Unit] =
        S.supervise(
          Stream
            .awakeEvery(sleepTime)
            .evalMap { _ =>
              thunk.handleErrorWith(err => daemonLogger.error(err)("The daemon encountered an error."))
            }
            .compile
            .drain
        ).void
    }

}
