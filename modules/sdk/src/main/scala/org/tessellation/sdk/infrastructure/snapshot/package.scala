package org.tessellation.sdk.infrastructure

import org.tessellation.schema.snapshot.Snapshot
import org.tessellation.schema.transaction.Transaction
import org.tessellation.schema.{Block, SnapshotOrdinal}
import org.tessellation.sdk.infrastructure.consensus.Consensus
import org.tessellation.security.signature.Signed

package object snapshot {

  type SnapshotEvent[T <: Transaction, B <: Block[T]] = Signed[B]

  type SnapshotKey = SnapshotOrdinal

  type SnapshotArtifact[T <: Transaction, B <: Block[T], S <: Snapshot[T, B]] = S

  type SnapshotConsensus[F[_], T <: Transaction, B <: Block[T], S <: Snapshot[T, B]] =
    Consensus[F, SnapshotEvent[T, B], SnapshotKey, SnapshotArtifact[T, B, S]]

}
