package io.constellationnetwork.currency.l0.snapshot.synchronous

import io.constellationnetwork.currency.l0.snapshot.synchronous.declaration.{AttemptDomain, PeerDeclaration}
import io.constellationnetwork.schema.peer.PeerId

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object message {

  @derive(encoder, decoder)
  final case class ConsensusPeerDeclaration[K, D <: PeerDeclaration](key: K, declaration: D)

  @derive(encoder, decoder)
  final case class ConsensusPeerDeclarationAck[K, Kind](key: K, kind: Kind, ack: Set[PeerId], domain: AttemptDomain)

  /** An authenticated, origin-scoped operator intent rather than evidence about one parent branch.
    *
    * This deliberately retains release/mainnet's key/kind shape: a peer can withdraw only itself because rumor authentication supplies the
    * map key at receipt, and its intent remains valid if the in-process attempt is recreated. Unlike Facilities, proposals, signatures, and
    * ACK observations, it is therefore not parent-domain-bound. Coordinated process restarts clear any undelivered rumor state.
    */
  @derive(encoder, decoder)
  final case class ConsensusWithdrawPeerDeclaration[K, Kind](key: K, kind: Kind)

  @derive(encoder, decoder)
  final case class ConsensusArtifact[K, A](key: K, artifact: A)

  @derive(encoder, decoder)
  final case class RegistrationResponse[Key](maybeKey: Option[Key])

  @derive(encoder, decoder)
  final case class GetConsensusOutcomeRequest[Key](key: Key)
}
