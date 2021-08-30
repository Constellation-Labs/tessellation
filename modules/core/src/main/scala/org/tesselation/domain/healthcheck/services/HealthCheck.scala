package org.tesselation.domain.healthcheck.services

import org.tesselation.domain.healthcheck.Status.AppStatus

trait HealthCheck[F[_]] {
  def status: F[AppStatus]
}
