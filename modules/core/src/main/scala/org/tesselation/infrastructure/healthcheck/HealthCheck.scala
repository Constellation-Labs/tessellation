package org.tesselation.infrastructure.healthcheck

import cats.effect.Temporal

import org.tesselation.domain.healthcheck.Status.{AppStatus, FooStatus, Okay}
import org.tesselation.domain.healthcheck.services.HealthCheck

object HealthCheck {

  def make[F[_]: Temporal](
    ): HealthCheck[F] =
    new HealthCheck[F] {

      def status: F[AppStatus] =
        Temporal[F].pure(AppStatus(FooStatus(Okay)))
    }
}
