package org.tessellation.sdk.domain.healthcheck.consensus.types

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.healthcheck.consensus.types.types.RoundId

import derevo.cats.show
import derevo.derive

@derive(show)
final case class HealthCheckRoundId(roundId: RoundId, owner: PeerId)
