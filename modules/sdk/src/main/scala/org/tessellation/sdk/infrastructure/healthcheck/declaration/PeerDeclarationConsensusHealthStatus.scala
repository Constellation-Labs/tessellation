package org.tessellation.sdk.infrastructure.healthcheck.declaration

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.healthcheck.consensus.types.{ConsensusHealthStatus, HealthCheckRoundId}

case class PeerDeclarationConsensusHealthStatus[K](
  key: PeerDeclarationHealthCheckKey[K],
  roundId: HealthCheckRoundId,
  owner: PeerId,
  status: PeerDeclarationHealth,
  clusterState: Set[PeerId]
) extends ConsensusHealthStatus[PeerDeclarationHealthCheckKey[K], PeerDeclarationHealth]
