package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.PeerDeclaration
import io.constellationnetwork.schema.peer.PeerId

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object message {

  @derive(encoder, decoder)
  case class ConsensusEvent[E](value: E)

  @derive(encoder, decoder)
  case class ConsensusPeerDeclaration[K, D <: PeerDeclaration](key: K, declaration: D)

  @derive(encoder, decoder)
  case class ConsensusPeerDeclarationAck[K, Kind](key: K, kind: Kind, ack: Set[PeerId])

  @derive(encoder, decoder)
  case class ConsensusWithdrawPeerDeclaration[K, Kind](key: K, kind: Kind)

  @derive(encoder, decoder)
  case class ConsensusArtifact[K, A](key: K, artifact: A)

  @derive(encoder, decoder)
  case class RegistrationResponse[Key](
    maybeKey: Option[Key]
  )

  @derive(encoder, decoder)
  case class GetConsensusOutcomeRequest[Key](
    key: Key
  )

}
