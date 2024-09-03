package io.constellationnetwork.schema

import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.snapshot.Snapshot
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.hash.{Hash, ProofsHash}

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

  def fromHashedSnapshot(snapshot: Hashed[Snapshot]): SnapshotReference =
    SnapshotReference(
      snapshot.height,
      snapshot.subHeight,
      snapshot.ordinal,
      snapshot.lastSnapshotHash,
      snapshot.hash,
      snapshot.proofsHash
    )
}
