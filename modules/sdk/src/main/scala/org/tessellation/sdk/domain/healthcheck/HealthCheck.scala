package org.tessellation.sdk.domain.healthcheck

import org.tessellation.schema.healthcheck.AppStatus

trait HealthCheck[F[_]] {
  def status: F[AppStatus]
}

