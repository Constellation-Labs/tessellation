package org.tessellation.dag.snapshot

import cats.data.NonEmptyList

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.schema.address.Address
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.hash.Hash

import derevo.cats.show
import derevo.derive

@derive(show)
case class GlobalSnapshot(
  ordinal: SnapshotOrdinal,
  height: Height,
  subHeight: SubHeight,
  lastSnapshotHash: Hash,
  blocks: Set[DAGBlock],
  stateChannelSnapshots: Map[Address, NonEmptyList[StateChannelSnapshotBinary]],
  nextFacilitators: NonEmptyList[PeerId]
)
