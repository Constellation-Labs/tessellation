package org.tessellation.node.shared.infrastructure

import org.tessellation.node.shared.infrastructure.consensus.Consensus
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.snapshot.Snapshot

package object snapshot {

  type SnapshotArtifact[S <: Snapshot] = S

  type SnapshotConsensus[F[_], S <: Snapshot, Context, Event] =
    Consensus[F, Event, SnapshotOrdinal, SnapshotArtifact[S], Context]

}
