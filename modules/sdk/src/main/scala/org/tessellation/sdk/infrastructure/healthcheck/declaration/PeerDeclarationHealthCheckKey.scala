package org.tessellation.sdk.infrastructure.healthcheck.declaration

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.healthcheck.consensus.types.HealthCheckKey
import org.tessellation.sdk.infrastructure.healthcheck.declaration.kind.PeerDeclarationKind

case class PeerDeclarationHealthCheckKey[K](id: PeerId, consensusKey: K, kind: PeerDeclarationKind)
    extends HealthCheckKey
