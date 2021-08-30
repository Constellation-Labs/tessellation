package org.tesselation.domain.healthcheck

import org.tesselation.schema.healthcheck.AppStatus

trait HealthCheck[F[_]] {
  def status: F[AppStatus]
}
