package org.tessellation.schema

import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.snapshot.Snapshot
import org.tessellation.security.Hashed
import org.tessellation.security.hash.{Hash, ProofsHash}

import derevo.cats.show
import derevo.derive

@derive(show)
case class SnapshotReference(
  height: Height,
  subHeight: SubHeight,
  ordinal: SnapshotOrdinal,
  lastSnapshotHash: Hash,
  hash: Hash,
  proofsHash: ProofsHash
)

object SnapshotReference {

  def fromHashedSnapshot(snapshot: Hashed[Snapshot[_]]): SnapshotReference =
    SnapshotReference(
      snapshot.height,
      snapshot.subHeight,
      snapshot.ordinal,
      snapshot.lastSnapshotHash,
      snapshot.hash,
      snapshot.proofsHash
    )
}
