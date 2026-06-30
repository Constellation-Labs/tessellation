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

  /** Signed per-peer timeout vote. Initially collected as inert evidence in parallel with ViewChangeVote/VCC so the cluster can prove
    * whether timeout quorum forms in the same stalls where local abandon loops fire. It does not mutate view or committee until the later
    * TC apply phase.
    */
  @derive(encoder, decoder)
  case class ConsensusPeerTimeoutVote[K](key: K, vote: Signed[TimeoutVote])

  /** Signed per-peer eviction vote. Same wire-envelope rationale as [[ConsensusPeerVote]] — EvictionCertificate assembly requires the
    * individual [[Signed]] proof per vote to survive end-to-end so the certificate embedded in a later Proposal remains independently
    * verifiable by any node reading the Proposal (including peers that never saw the original gossip).
    */
  @derive(encoder, decoder)
  case class ConsensusPeerEvictionVote[K](key: K, vote: Signed[EvictionVote])

  /** Signed per-peer admission vote (B2). Same wire-envelope rationale as [[ConsensusPeerEvictionVote]]. */
  @derive(encoder, decoder)
  case class ConsensusPeerAdmissionVote[K](key: K, vote: Signed[AdmissionVote])

  /** Re-distribution of a locally-assembled `ViewChangeCertificate` so peers that did NOT see local quorum for the same `(fromView,
    * toView)` transition (due to gossip lag) can still store the VCC and proceed when they become leader at the advanced view. Without
    * this, any peer whose local view-change-vote count was below the quorum threshold at assembly time advances state via gossip-of-state
    * but never calls `storeAssembledVcc`, and the next time it leads at `view > 0` the proposal builder wedges with
    * `vcc_missing_for_view_gt_0`. Wedge observed on testnet alpha.87 at ord 3127026: .193 saw only 1 of 2 required VCVs for the 14->15
    * transition before state advanced, so .193's local VCC slot remained empty; when .193 became leader at view 16 the proposal builder
    * bailed. With this rumor, the first peer that assembles the VCC broadcasts it to the committee and every receiver stores it locally.
    */
  @derive(encoder, decoder)
  case class ConsensusAssembledVcc[K](key: K, vcc: ViewChangeCertificate)

  @derive(encoder, decoder)
  case class RegistrationResponse[Key](
    maybeKey: Option[Key]
  )

  @derive(encoder, decoder)
  case class GetConsensusOutcomeRequest[Key](
    key: Key
  )

}
