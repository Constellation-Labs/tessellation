package org.tessellation.sdk.domain.block.processing

import cats.data.EitherT

import org.tessellation.schema.Block
import org.tessellation.sdk.domain.block.processing.{TxChains, UsageCount}
import org.tessellation.security.signature.Signed

trait BlockAcceptanceLogic[F[_], B <: Block] {
  def acceptBlock(
    block: Signed[B],
    txChains: TxChains,
    context: BlockAcceptanceContext[F],
    contextUpdate: BlockAcceptanceContextUpdate
  ): EitherT[F, BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)]

}
