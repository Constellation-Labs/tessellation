package io.constellationnetwork.schema

import scala.collection.immutable.SortedSet

import io.constellationnetwork.schema.peer.PeerId

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

/** One finalized round's canonical controller evidence.
  *
  * Lives in `modules/shared` (stage 4 move from `node-shared` infrastructure/consensus) so `ConsensusOperationalState` can carry the
  * bounded evidence window across the snapshot / sidecar persistence boundary. `node-shared` re-exports the name from its
  * `infrastructure.consensus` package object so existing layer imports keep resolving.
  *
  * Every field is a consensus-agreed fact recorded at outcome finalization by the StateAdvancers, so every honest node writes the
  * byte-identical entry for the same ordinal:
  *
  *   - `roundStartFacilitators`: the canonical committee frozen at round creation (`state.roundStartFacilitators`).
  *   - `completedSigners`: the canonical signer set for the completed round -- `roundStartFacilitators` minus evicted peers, the SAME
  *     derivation the `recentSigners` window uses. NOT `signedMajorityArtifact.proofs`: the proofs set is local-observed (quorum-cutoff
  *     races) and documented as divergent across nodes.
  *   - `timeoutVoters`: voters of the TimeoutCertificate embedded in the ACCEPTED proposal (`state.acceptedTimeoutCertificateVoters`),
  *     never a local timeout-cache observation.
  *   - `admittedPeers`: targets of quorum-signed AdmissionCertificates applied to the round.
  *   - `evictedPeers`: targets of quorum-signed EvictionCertificates applied to the round (certificate-applied only; facility-phase
  *     fork-evictions are excluded).
  */
@derive(encoder, decoder, eqv, show)
final case class ControllerEvidenceEntry(
  roundStartFacilitators: SortedSet[PeerId],
  completedSigners: SortedSet[PeerId],
  timeoutVoters: SortedSet[PeerId],
  admittedPeers: SortedSet[PeerId],
  evictedPeers: SortedSet[PeerId]
)

object ControllerEvidenceEntry {
  val empty: ControllerEvidenceEntry =
    ControllerEvidenceEntry(
      roundStartFacilitators = SortedSet.empty,
      completedSigners = SortedSet.empty,
      timeoutVoters = SortedSet.empty,
      admittedPeers = SortedSet.empty,
      evictedPeers = SortedSet.empty
    )
}
