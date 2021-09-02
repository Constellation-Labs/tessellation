package org.tesselation.infrastructure.healthcheck

import cats.effect.Temporal

import org.tesselation.domain.healthcheck.HealthCheck
import org.tesselation.schema.healthcheck.Status.Okay
import org.tesselation.schema.healthcheck.{AppStatus, FooStatus}

object HealthCheck {

  def make[F[_]: Temporal]: HealthCheck[F] =
    new HealthCheck[F] {

      def status: F[AppStatus] =
        Temporal[F].pure(AppStatus(FooStatus(Okay)))
    }
}
