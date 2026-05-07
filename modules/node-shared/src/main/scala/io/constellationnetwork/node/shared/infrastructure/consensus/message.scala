package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
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

  /** Assembled eviction certificate gossiped between facilitators the moment any node assembles quorum. Without this carrier the cert is
    * only consumed at proposal-acceptance time of the NEXT ordinal, so a stuck retry-loop at the same ordinal can collect quorum on every
    * attempt without ever applying the eviction. Receivers re-validate the cert structurally before storing it so a malformed cert from a
    * buggy peer cannot poison local state. The cert itself carries quorum-many [[Signed]] votes — those proofs are the authority, the
    * envelope is just routing.
    */
  @derive(encoder, decoder)
  case class ConsensusPeerEvictionCertificate[K](key: K, cert: EvictionCertificate)

  /** Signed per-peer admission vote (B2). Same wire-envelope rationale as [[ConsensusPeerEvictionVote]]. */
  @derive(encoder, decoder)
  case class ConsensusPeerAdmissionVote[K](key: K, vote: Signed[AdmissionVote])

  @derive(encoder, decoder)
  case class RegistrationResponse[Key](
    maybeKey: Option[Key]
  )

  @derive(encoder, decoder)
  case class GetConsensusOutcomeRequest[Key](
    key: Key
  )

}
