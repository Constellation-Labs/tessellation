package org.tessellation.infrastructure.healthcheck

import cats.effect.Temporal

import org.tessellation.domain.healthcheck.HealthCheck
import org.tessellation.schema.healthcheck.Status.Okay
import org.tessellation.schema.healthcheck.{AppStatus, FooStatus}

object HealthCheck {

  def make[F[_]: Temporal]: HealthCheck[F] =
    new HealthCheck[F] {

      def status: F[AppStatus] =
        Temporal[F].pure(AppStatus(FooStatus(Okay)))
    }
}
