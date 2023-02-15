package org.tessellation.sdk.domain.block.processing

import cats.data.EitherT

import org.tessellation.schema.Block
import org.tessellation.schema.transaction.Transaction
import org.tessellation.sdk.domain.block.processing.{TxChains, UsageCount}
import org.tessellation.security.signature.Signed

trait BlockAcceptanceLogic[F[_], T <: Transaction, B <: Block[T]] {
  def acceptBlock(
    block: Signed[B],
    txChains: TxChains[T],
    context: BlockAcceptanceContext[F],
    contextUpdate: BlockAcceptanceContextUpdate
  ): EitherT[F, BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)]

}
