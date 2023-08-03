package org.tessellation.sdk.infrastructure

import org.tessellation.schema.snapshot.Snapshot
import org.tessellation.schema.{Block, SnapshotOrdinal}
import org.tessellation.sdk.infrastructure.consensus.Consensus

package object snapshot {

  type SnapshotArtifact[B <: Block, S <: Snapshot[B]] = S

  type SnapshotConsensus[F[_], B <: Block, S <: Snapshot[B], Context, Event] =
    Consensus[F, Event, SnapshotOrdinal, SnapshotArtifact[B, S], Context]

}
