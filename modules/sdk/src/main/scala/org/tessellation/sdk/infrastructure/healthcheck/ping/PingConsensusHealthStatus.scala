package org.tessellation.sdk.infrastructure.healthcheck.ping

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.healthcheck.consensus.types.{ConsensusHealthStatus, HealthCheckRoundId}

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
case class PingConsensusHealthStatus(
  key: PingHealthCheckKey,
  roundIds: Set[HealthCheckRoundId],
  owner: PeerId,
  status: PingHealthCheckStatus,
  clusterState: Set[PeerId]
) extends ConsensusHealthStatus[PingHealthCheckKey, PingHealthCheckStatus]
