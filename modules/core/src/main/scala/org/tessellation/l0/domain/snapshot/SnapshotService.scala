package org.tessellation.l0.domain.snapshot
import cats.data.NonEmptySet

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.kernel.StateChannelSnapshot
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.Hashed

trait SnapshotService[F[_]] {

  def createSnapshot(
    blocks: NonEmptySet[Hashed[DAGBlock]],
    snapshots: Set[StateChannelSnapshot],
    nextFacilitators: NonEmptySet[PeerId]
  ): F[GlobalSnapshot]
}
