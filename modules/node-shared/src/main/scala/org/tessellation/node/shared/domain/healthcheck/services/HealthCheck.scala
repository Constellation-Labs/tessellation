package org.tessellation.node.shared.domain.healthcheck.services

trait HealthCheck[F[_]] {

  def trigger(): F[Unit]

}
