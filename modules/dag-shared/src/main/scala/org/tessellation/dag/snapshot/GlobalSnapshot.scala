package org.tessellation.dag.snapshot

import cats.data.NonEmptyList

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.schema.address.Address
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder, eqv, show)
case class GlobalSnapshot(
  ordinal: SnapshotOrdinal,
  height: Height,
  subHeight: SubHeight,
  lastSnapshotHash: Hash,
  blocks: Set[Signed[DAGBlock]],
  stateChannelSnapshots: Map[Address, NonEmptyList[StateChannelSnapshotBinary]],
  nextFacilitators: NonEmptyList[PeerId],
  info: GlobalSnapshotInfo
)
