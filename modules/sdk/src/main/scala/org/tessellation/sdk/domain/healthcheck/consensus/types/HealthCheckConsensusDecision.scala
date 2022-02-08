package org.tessellation.sdk.domain.healthcheck.consensus.types

sealed trait HealthCheckConsensusDecision

final case class PositiveOutcome() extends HealthCheckConsensusDecision

final case class NegativeOutcome() extends HealthCheckConsensusDecision
