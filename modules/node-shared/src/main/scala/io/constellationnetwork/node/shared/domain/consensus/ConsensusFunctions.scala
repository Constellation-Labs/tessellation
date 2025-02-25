package io.constellationnetwork.node.shared.domain.consensus

import scala.util.control.NoStackTrace

import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions.InvalidArtifact
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, SnapshotOrdinal}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher}

trait ConsensusFunctions[F[_], Event, Key, Artifact, Context] {

  def triggerPredicate(event: Event): Boolean

  def facilitatorFilter(lastSignedArtifact: Signed[Artifact], lastContext: Context, peerId: PeerId): F[Boolean]

  def validateArtifact(
    lastSignedArtifact: Signed[Artifact],
    lastContext: Context,
    trigger: ConsensusTrigger,
    artifact: Artifact,
    facilitators: Set[PeerId],
    lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
  )(implicit hasher: Hasher[F]): F[Either[InvalidArtifact, (Artifact, Context)]]

  def createProposalArtifact(
    lastKey: Key,
    lastArtifact: Signed[Artifact],
    lastContext: Context,
    lastArtifactHasher: Hasher[F],
    trigger: ConsensusTrigger,
    events: Set[Event],
    facilitators: Set[PeerId],
    lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
  )(implicit hasher: Hasher[F]): F[(Artifact, Context, Set[Event])]
}

object ConsensusFunctions {
  trait InvalidArtifact extends NoStackTrace
}
