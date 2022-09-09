package org.tessellation.sdk.infrastructure.healthcheck.declaration

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.healthcheck.consensus.types.{ConsensusHealthStatus, HealthCheckRoundId}

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
case class PeerDeclarationConsensusHealthStatus[K](
  key: PeerDeclarationHealthCheckKey[K],
  roundIds: Set[HealthCheckRoundId],
  owner: PeerId,
  status: PeerDeclarationHealth,
  clusterState: Set[PeerId]
) extends ConsensusHealthStatus[PeerDeclarationHealthCheckKey[K], PeerDeclarationHealth]
