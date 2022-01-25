package org.tessellation.l0.domain.snapshot

import cats.data.NonEmptySet
import eu.timepit.refined.types.numeric.NonNegLong
import io.estatico.newtype.macros.newtype
import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.kernel.StateChannelSnapshot
import org.tessellation.schema.height.Height
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.signature.Signed
import io.estatico.newtype.ops._
import eu.timepit.refined.auto._

@newtype
case class GlobalSnapshotOrdinal(value: NonNegLong)

case class GlobalSnapshot(
  ordinal: GlobalSnapshotOrdinal,
  height: Height,
  subHeight: Height,
  blocks: Set[Signed[DAGBlock]],
  snapshots: Set[StateChannelSnapshot],
  nextFacilitators: NonEmptySet[PeerId]
) {}
