package org.tessellation.dag.l1.infrastructure.healthcheck

import cats.effect.{Async, Spawn}
import cats.syntax.functor._

import scala.concurrent.duration._

import org.tessellation.dag.l1.modules.HealthChecks
import org.tessellation.sdk.domain.Daemon

import fs2._

trait HealthCheckDaemon[F[_]] extends Daemon[F] {}

object HealthCheckDaemon {

  def make[F[_]: Async](healthChecks: HealthChecks[F]): HealthCheckDaemon[F] = new HealthCheckDaemon[F] {

    def start: F[Unit] =
      Spawn[F].start(periodic).void

    private def periodic: F[Unit] =
      Stream
        .awakeEvery(10.seconds)
        .evalTap { _ =>
          healthChecks.ping.trigger()
        }
        .compile
        .drain
  }
}
