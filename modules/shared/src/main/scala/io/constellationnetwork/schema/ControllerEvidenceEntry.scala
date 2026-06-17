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
  *   - `completedSigners`: the canonical completed-signer set for the round, derived by
  *     `ControllerEvidenceDerivation.canonicalCompletedSigners` -- the frozen round-start committee restricted to the quorum-accepted
  *     proposal's `observedResponders` (full committee while that set is empty, i.e. bootstrap), minus certificate-applied evictions; the
  *     SAME derivation the `recentSigners` window uses. NOT `signedMajorityArtifact.proofs` (local-observed, quorum-cutoff accretion races)
  *     and NOT `roundStartFacilitators -- state.removedFacilitators` (the fork-eviction component of `removedFacilitators` is computed from
  *     the local declaration snapshot at quorum-crossing and diverges across honest nodes -- the ordinal-3150166 wedge). See the
  *     determinism argument on `canonicalCompletedSigners`.
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
