package org.tessellation.sdk

import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo}
import org.tessellation.schema.{Block, SnapshotOrdinal}
import org.tessellation.sdk.infrastructure.consensus.Consensus
import org.tessellation.security.signature.Signed

package object snapshot {

  type SnapshotEvent = Signed[Block]

  type SnapshotKey = SnapshotOrdinal

  type SnapshotArtifact[S <: Snapshot[_]] = S

  type SnapshotContext[C <: SnapshotInfo[_]] = C

  type SnapshotConsensus[F[_], S <: Snapshot[_], C <: SnapshotInfo[_]] = Consensus[F, SnapshotEvent, SnapshotKey, SnapshotArtifact[S], C]

}
