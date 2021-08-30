package org.tesselation.modules

import cats.effect.kernel.Temporal

import org.tesselation.infrastructure.healthcheck.{Services => HealthCheckServices}

object Services {

  def make[F[_]: Temporal](): Services[F] =
    new Services[F](
      healthcheck = HealthCheckServices.make[F]
    ) {}
}

sealed abstract class Services[F[_]] private (
  val healthcheck: HealthCheckServices[F]
)
