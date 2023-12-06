package org.tessellation.node.shared.domain.healthcheck.consensus

import org.tessellation.node.shared.domain.healthcheck.consensus.types._
import org.tessellation.schema.peer.PeerId

trait HealthCheckConsensusDriver[K <: HealthCheckKey, A <: HealthCheckStatus, B <: ConsensusHealthStatus[
  K,
  A
], C <: HealthCheckConsensusDecision] {

  def removePeersWithParallelRound: Boolean

  def calculateConsensusOutcome(
    key: K,
    ownStatus: A,
    selfId: PeerId,
    receivedStatuses: List[B]
  ): C

  def consensusHealthStatus(
    key: K,
    ownStatus: A,
    roundIds: Set[HealthCheckRoundId],
    selfId: PeerId,
    clusterState: Set[PeerId]
  ): B
}
