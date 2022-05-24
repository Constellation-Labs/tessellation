package org.tessellation.sdk.infrastructure.healthcheck.ping

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.healthcheck.consensus.types.{ConsensusHealthStatus, HealthCheckRoundId}

case class PingConsensusHealthStatus(
  key: PingHealthCheckKey,
  roundId: HealthCheckRoundId,
  owner: PeerId,
  status: PingHealthCheckStatus,
  clusterState: Set[PeerId]
) extends ConsensusHealthStatus[PingHealthCheckKey, PingHealthCheckStatus]
