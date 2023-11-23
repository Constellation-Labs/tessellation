package org.tessellation.node.shared.domain.healthcheck.consensus.types

sealed trait HealthCheckRoundType

case object OwnRound extends HealthCheckRoundType
case object PeerRound extends HealthCheckRoundType
