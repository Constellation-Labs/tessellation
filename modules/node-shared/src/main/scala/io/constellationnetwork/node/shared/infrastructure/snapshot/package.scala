package io.constellationnetwork.node.shared.infrastructure

import io.constellationnetwork.node.shared.infrastructure.consensus.Consensus
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.snapshot.Snapshot

package object snapshot {

  type SnapshotArtifact[S <: Snapshot] = S

  type SnapshotConsensus[F[_], S <: Snapshot, Context, Event, StateKey, Status, Outcome, Kind] =
    Consensus[F, Event, StateKey, SnapshotOrdinal, SnapshotArtifact[S], Context, Status, Outcome, Kind]

}
