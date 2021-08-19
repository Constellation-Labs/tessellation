package org.tesselation.services

import cats.effect.Temporal

import org.tesselation.domain.healthcheck.Status.Okay
import org.tesselation.domain.healthcheck.{AppStatus, FooStatus}

trait HealthCheck[F[_]] {
  def status: F[AppStatus]
}

object HealthCheck {

  def make[F[_]: Temporal](
    ): HealthCheck[F] =
    new HealthCheck[F] {

      def status: F[AppStatus] =
        Temporal[F].pure(AppStatus(FooStatus(Okay)))
    }
}
