package org.tessellation.l0.domain.snapshot

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.l0.schema.snapshot.GlobalSnapshot
import org.tessellation.security.signature.Signed

trait SnapshotService[F[_]] {
  def createSnapshot(): F[GlobalSnapshot]
  def createSnapshot(blocks: Set[Signed[DAGBlock]]): F[GlobalSnapshot]
}
