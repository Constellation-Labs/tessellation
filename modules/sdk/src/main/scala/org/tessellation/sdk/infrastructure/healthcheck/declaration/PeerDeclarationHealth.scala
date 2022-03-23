package org.tessellation.sdk.infrastructure.healthcheck.declaration

import org.tessellation.sdk.domain.healthcheck.consensus.types.HealthCheckStatus

import derevo.cats.{eqv, show}
import derevo.derive

@derive(eqv, show)
sealed trait PeerDeclarationHealth extends HealthCheckStatus

/** Peer is either not a facilitator or his declaration is not required in the current state of consensus */
case object NotRequired extends PeerDeclarationHealth
case object Received extends PeerDeclarationHealth
case object Awaiting extends PeerDeclarationHealth
case object TimedOut extends PeerDeclarationHealth
