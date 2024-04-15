package org.tessellation.node.shared.domain.block.processing

import org.tessellation.schema.{Block, SnapshotOrdinal}
import org.tessellation.security.Hasher
import org.tessellation.security.signature.Signed

trait BlockAcceptanceManager[F[_]] {

  def acceptBlocksIteratively(
    blocks: List[Signed[Block]],
    context: BlockAcceptanceContext[F],
    snapshotOrdinal: SnapshotOrdinal
  )(implicit hasher: Hasher[F]): F[BlockAcceptanceResult]

  def acceptBlock(
    block: Signed[Block],
    context: BlockAcceptanceContext[F],
    snapshotOrdinal: SnapshotOrdinal
  )(implicit hasher: Hasher[F]): F[Either[BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)]]

}
