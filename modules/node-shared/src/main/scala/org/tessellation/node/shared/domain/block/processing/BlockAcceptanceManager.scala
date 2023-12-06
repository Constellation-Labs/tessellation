package org.tessellation.node.shared.domain.block.processing

import org.tessellation.node.shared.domain.block.processing.UsageCount
import org.tessellation.schema.Block
import org.tessellation.security.signature.Signed

trait BlockAcceptanceManager[F[_]] {

  def acceptBlocksIteratively(
    blocks: List[Signed[Block]],
    context: BlockAcceptanceContext[F]
  ): F[BlockAcceptanceResult]

  def acceptBlock(
    block: Signed[Block],
    context: BlockAcceptanceContext[F]
  ): F[Either[BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)]]

}
