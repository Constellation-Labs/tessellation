package org.tessellation.sdk.domain.healthcheck.consensus

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.healthcheck.consensus.types._

trait HealthCheckConsensusDriver[K <: HealthCheckKey, A <: HealthCheckStatus, B <: ConsensusHealthStatus[K, A], C <: HealthCheckConsensusDecision] {

  def removePeersWithParallelRound: Boolean

  def calculateConsensusOutcome(
    key: K,
    ownStatus: A,
    selfId: PeerId,
    receivedStatuses: List[B]
  ): C

  def consensusHealthStatus(key: K, ownStatus: A, roundId: HealthCheckRoundId, selfId: PeerId): B
}
