package org.tessellation.sdk.domain.block.processing

import org.tessellation.schema.Block
import org.tessellation.sdk.domain.block.processing.UsageCount
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
