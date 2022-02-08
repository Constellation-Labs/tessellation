package org.tessellation.sdk.domain.healthcheck.consensus.types

import org.tessellation.schema.peer.PeerId

sealed trait HealthCheckConsensusDecision

final case class PositiveOutcome(peer: PeerId) extends HealthCheckConsensusDecision

final case class NegativeOutcome(peer: PeerId) extends HealthCheckConsensusDecision
