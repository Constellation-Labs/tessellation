package org.tessellation.node.shared.domain.block.processing

import org.tessellation.schema.{Block, SnapshotOrdinal}
import org.tessellation.security.signature.Signed

trait BlockAcceptanceManager[F[_]] {

  def acceptBlocksIteratively(
    blocks: List[Signed[Block]],
    context: BlockAcceptanceContext[F],
    snapshotOrdinal: SnapshotOrdinal
  ): F[BlockAcceptanceResult]

  def acceptBlock(
    block: Signed[Block],
    context: BlockAcceptanceContext[F],
    snapshotOrdinal: SnapshotOrdinal
  ): F[Either[BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)]]

}
