package org.tessellation.sdk.domain.healthcheck.consensus.types

import org.tessellation.schema.peer.PeerId

trait ConsensusHealthStatus[+A <: HealthCheckStatus] {
  def key: HealthCheckKey
  def roundId: HealthCheckRoundId
  def owner: PeerId
  def status: A
}
