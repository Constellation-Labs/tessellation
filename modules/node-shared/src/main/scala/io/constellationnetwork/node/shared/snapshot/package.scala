package io.constellationnetwork.node.shared

import io.constellationnetwork.node.shared.infrastructure.consensus.Consensus
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo}
import io.constellationnetwork.schema.{Block, SnapshotOrdinal}
import io.constellationnetwork.security.signature.Signed

package object snapshot {

  type SnapshotEvent = Signed[Block]

  type SnapshotKey = SnapshotOrdinal

  type SnapshotArtifact[S <: Snapshot] = S

  type SnapshotContext[C <: SnapshotInfo[_]] = C

  type SnapshotConsensus[F[_], S <: Snapshot, C <: SnapshotInfo[_], Status, Outcome, Kind] =
    Consensus[F, SnapshotEvent, SnapshotKey, SnapshotArtifact[S], C, Status, Outcome, Kind]

}
