package org.tessellation.sdk.infrastructure.healthcheck.declaration

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.healthcheck.consensus.types.HealthCheckKey

sealed trait PeerDeclarationKind

case object Facility extends PeerDeclarationKind
case object Proposal extends PeerDeclarationKind
case object Signature extends PeerDeclarationKind

case class PeerDeclarationHealthCheckKey[K](id: PeerId, consensusKey: K, kind: PeerDeclarationKind)
    extends HealthCheckKey
