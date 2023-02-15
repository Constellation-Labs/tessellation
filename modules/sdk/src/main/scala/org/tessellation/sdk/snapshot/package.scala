package org.tessellation.sdk

import org.tessellation.schema.snapshot.Snapshot
import org.tessellation.schema.{Block, SnapshotOrdinal}
import org.tessellation.sdk.infrastructure.consensus.Consensus
import org.tessellation.security.signature.Signed

package object snapshot {

  type SnapshotEvent = Signed[Block[_]]

  type SnapshotKey = SnapshotOrdinal

  type SnapshotArtifact[S <: Snapshot[_, _]] = S

  type SnapshotConsensus[F[_], S <: Snapshot[_, _]] = Consensus[F, SnapshotEvent, SnapshotKey, SnapshotArtifact[S]]

}
