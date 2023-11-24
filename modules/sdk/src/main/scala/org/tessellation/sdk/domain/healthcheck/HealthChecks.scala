package org.tessellation.sdk.domain.healthcheck

trait HealthChecks[F[_]] {
  def trigger(): F[Unit]
}
