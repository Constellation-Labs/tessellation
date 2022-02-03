package org.tessellation.sdk.domain.healthcheck.types

trait ConsensusHealthStatus[+A <: HealthCheckStatus] {
  def key: HealthCheckKey
  def roundId: HealthCheckRoundId
  def status: A
}
