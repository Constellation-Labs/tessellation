package org.tessellation.sdk.domain.healthcheck.consensus

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.healthcheck.consensus.types._

trait HealthCheckConsensusDriver[A <: HealthCheckStatus, B <: ConsensusHealthStatus[A]] {
  def calculateConsensusOutcome(key: HealthCheckKey, ownStatus: A, selfId: PeerId, receivedStatuses: List[B]): HealthCheckConsensusDecision
  def consensusHealthStatus(key: HealthCheckKey, ownStatus: A, roundId: HealthCheckRoundId, selfId: PeerId): B
}
