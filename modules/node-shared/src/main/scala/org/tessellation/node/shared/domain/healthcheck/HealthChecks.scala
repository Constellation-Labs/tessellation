package org.tessellation.node.shared.domain.healthcheck

trait HealthChecks[F[_]] {
  def trigger(): F[Unit]
}
