package org.tessellation.sdk.domain.healthcheck.consensus.types

import org.tessellation.schema.peer.PeerId

trait ConsensusHealthStatus[K <: HealthCheckKey, A <: HealthCheckStatus] {
  def key: K
  def roundId: HealthCheckRoundId
  def owner: PeerId
  def status: A
  def clusterState: Set[PeerId]
}
