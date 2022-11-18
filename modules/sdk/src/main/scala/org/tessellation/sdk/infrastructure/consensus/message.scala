package org.tessellation.sdk.infrastructure.consensus

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.infrastructure.consensus.declaration.PeerDeclaration
import org.tessellation.sdk.infrastructure.consensus.declaration.kind.PeerDeclarationKind

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object message {

  @derive(eqv, show, encoder, decoder)
  case class ConsensusEvent[E](value: E)

  @derive(eqv, show, encoder, decoder)
  case class ConsensusPeerDeclaration[K, D <: PeerDeclaration](key: K, declaration: D)

  @derive(eqv, show, encoder, decoder)
  case class ConsensusPeerDeclarationAck[K](key: K, kind: PeerDeclarationKind, ack: Set[PeerId])

  @derive(eqv, show, encoder, decoder)
  case class ConsensusArtifact[K, A](key: K, artifact: A)

}
