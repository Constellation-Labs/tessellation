package org.tessellation.sdk.domain.block.processing

import org.tessellation.schema.Block
import org.tessellation.schema.transaction.Transaction
import org.tessellation.sdk.domain.block.processing.UsageCount
import org.tessellation.security.signature.Signed

trait BlockAcceptanceManager[F[_], T <: Transaction, B <: Block[T]] {

  def acceptBlocksIteratively(
    blocks: List[Signed[B]],
    context: BlockAcceptanceContext[F]
  ): F[BlockAcceptanceResult[B]]

  def acceptBlock(
    block: Signed[B],
    context: BlockAcceptanceContext[F]
  ): F[Either[BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)]]

}
