package org.tessellation.sdk.domain.healthcheck.services

trait HealthCheck[F[_]] {

  def trigger(): F[Unit]

}
