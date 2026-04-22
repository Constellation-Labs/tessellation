package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{EvictionVote, PeerDeclaration, ViewChangeVote}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.signature.Signed

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

  /** Signed per-peer view-change vote. Separate from [[ConsensusPeerDeclaration]] because VCC assembly requires the individual [[Signed]]
    * proof per vote (the envelope signature isn't sufficient — VCCs embedded in proposals must be independently verifiable).
    */
  @derive(encoder, decoder)
  case class ConsensusPeerVote[K](key: K, vote: Signed[ViewChangeVote])

  /** Signed per-peer eviction vote. Same wire-envelope rationale as [[ConsensusPeerVote]] — EvictionCertificate assembly requires the
    * individual [[Signed]] proof per vote to survive end-to-end so the certificate embedded in a later Proposal remains independently
    * verifiable by any node reading the Proposal (including peers that never saw the original gossip).
    */
  @derive(encoder, decoder)
  case class ConsensusPeerEvictionVote[K](key: K, vote: Signed[EvictionVote])

  @derive(encoder, decoder)
  case class RegistrationResponse[Key](
    maybeKey: Option[Key]
  )

  @derive(encoder, decoder)
  case class GetConsensusOutcomeRequest[Key](
    key: Key
  )

}
