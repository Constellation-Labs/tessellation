package org.tessellation.sdk.domain.healthcheck.types

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.healthcheck.types.types.RoundId

final case class HealthCheckRoundId(roundId: RoundId, owner: PeerId)
