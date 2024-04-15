package org.tessellation.node.shared.domain.block.processing

import cats.data.EitherT

import org.tessellation.node.shared.domain.block.processing.{TxChains, UsageCount}
import org.tessellation.schema.Block
import org.tessellation.security.signature.Signed
import org.tessellation.security.Hasher

trait BlockAcceptanceLogic[F[_]] {
  def acceptBlock(
    block: Signed[Block],
    txChains: TxChains,
    context: BlockAcceptanceContext[F],
    contextUpdate: BlockAcceptanceContextUpdate
  )(implicit hasher: Hasher[F]): EitherT[F, BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)]

}
