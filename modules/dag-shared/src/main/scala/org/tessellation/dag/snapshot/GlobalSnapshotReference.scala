package org.tessellation.dag.snapshot

import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.security.Hashed
import org.tessellation.security.hash.{Hash, ProofsHash}

import derevo.cats.show
import derevo.derive

@derive(show)
case class GlobalSnapshotReference(
  height: Height,
  subHeight: SubHeight,
  ordinal: SnapshotOrdinal,
  lastSnapshotHash: Hash,
  hash: Hash,
  proofsHash: ProofsHash
)

object GlobalSnapshotReference {

  def fromHashedGlobalSnapshot(snapshot: Hashed[GlobalSnapshot]): GlobalSnapshotReference =
    GlobalSnapshotReference(
      snapshot.height,
      snapshot.subHeight,
      snapshot.ordinal,
      snapshot.lastSnapshotHash,
      snapshot.hash,
      snapshot.proofsHash
    )
}
