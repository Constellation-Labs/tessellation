package org.tessellation.sdk.infrastructure

import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.snapshot.Snapshot
import org.tessellation.sdk.infrastructure.consensus.Consensus

package object snapshot {

  type SnapshotArtifact[S <: Snapshot] = S

  type SnapshotConsensus[F[_], S <: Snapshot, Context, Event] =
    Consensus[F, Event, SnapshotOrdinal, SnapshotArtifact[S], Context]

}
