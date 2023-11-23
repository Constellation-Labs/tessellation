package org.tessellation.node.shared.infrastructure.healthcheck.ping

import org.tessellation.node.shared.domain.healthcheck.consensus.types.{ConsensusHealthStatus, HealthCheckRoundId}
import org.tessellation.schema.peer.PeerId

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
