package org.tessellation.node.shared.domain.healthcheck.consensus.types

import org.tessellation.node.shared.domain.healthcheck.consensus.types.types.RoundId
import org.tessellation.schema.peer.PeerId

import derevo.cats.show
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(show, encoder, decoder)
final case class HealthCheckRoundId(roundId: RoundId, owner: PeerId)
