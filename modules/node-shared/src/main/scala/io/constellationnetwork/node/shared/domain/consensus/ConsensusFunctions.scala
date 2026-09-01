package io.constellationnetwork.node.shared.domain.consensus

import scala.util.control.NoStackTrace

import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions.InvalidArtifact
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.schema.consensus.CertifiedLineageEvidenceV1
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{ConsensusOperationalState, GlobalIncrementalSnapshot, SnapshotOrdinal}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher}

trait ConsensusFunctions[F[_], Event, Key, Artifact, Context] {

  def triggerPredicate(event: Event): Boolean

  def facilitatorFilter(lastSignedArtifact: Signed[Artifact], lastContext: Context, peerId: PeerId): F[Boolean]

  // `peerHistory` is the deterministic snapshot of the previous round's peer-behavior
  // counters that the leader places into the artifact. Validators receive the leader's
  // artifact and re-execute by passing the same value here -- both leader and validator
  // pack it from their own (consensus-agreed) `state.lastOutcome`. Default `None` keeps
  // older tests and non-snapshot consumers from having to thread the argument.
  def validateArtifact(
    lastSignedArtifact: Signed[Artifact],
    lastContext: Context,
    trigger: ConsensusTrigger,
    artifact: Artifact,
    facilitators: Set[PeerId],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    peerHistory: Option[ConsensusOperationalState] = None,
    // v35: exact leader-carried lineage envelope. Followers must pass the
    // already-verified artifact field rather than reconstructing a local proof envelope.
    certifiedLineage: Option[CertifiedLineageEvidenceV1] = None
  )(implicit hasher: Hasher[F]): F[Either[InvalidArtifact, (Artifact, Context)]]

  def createProposalArtifact(
    lastKey: Key,
    lastArtifact: Signed[Artifact],
    lastContext: Context,
    lastArtifactHasher: Hasher[F],
    trigger: ConsensusTrigger,
    events: Set[Event],
    facilitators: Set[PeerId],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    peerHistory: Option[ConsensusOperationalState] = None,
    certifiedLineage: Option[CertifiedLineageEvidenceV1] = None
  )(implicit hasher: Hasher[F]): F[(Artifact, Context, Set[Event])]
}

object ConsensusFunctions {
  trait InvalidArtifact extends NoStackTrace
}
