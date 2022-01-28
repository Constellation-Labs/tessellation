package org.tessellation.sdk.domain.healthcheck

class HealthCheckRound[F[_]] {
  def isFinished: F[Boolean] = ???
}
