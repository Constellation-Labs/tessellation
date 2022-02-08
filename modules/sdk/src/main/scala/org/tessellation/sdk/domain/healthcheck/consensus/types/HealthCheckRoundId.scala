package org.tessellation.sdk.domain.healthcheck.consensus.types

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.healthcheck.consensus.types.types.RoundId

final case class HealthCheckRoundId(roundId: RoundId, owner: PeerId)
