package org.tessellation.l0.domain.snapshot

import cats.data.NonEmptySet

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.kernel.StateChannelSnapshot
import org.tessellation.schema.height.Height
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.signature.Signed

import io.estatico.newtype.ops._

case class GlobalSnapshot(
  blocks: Set[Signed[DAGBlock]],
  snapshots: Set[StateChannelSnapshot],
  nextFacilitators: NonEmptySet[PeerId]
) {
  def height: Height = blocks.map(_.value.height).maxBy(_.coerce)
}
