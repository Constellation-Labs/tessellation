package org.tessellation.sdk.infrastructure.healthcheck.declaration

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.healthcheck.consensus.types.HealthCheckKey
import org.tessellation.sdk.infrastructure.healthcheck.declaration.kind.PeerDeclarationKind

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}

case class PeerDeclarationHealthCheckKey[K](id: PeerId, consensusKey: K, kind: PeerDeclarationKind)
    extends HealthCheckKey

object PeerDeclarationHealthCheckKey {
  implicit def encoder[K: Encoder]: Encoder[PeerDeclarationHealthCheckKey[K]] = deriveEncoder
  implicit def decoder[K: Decoder]: Decoder[PeerDeclarationHealthCheckKey[K]] = deriveDecoder
}
