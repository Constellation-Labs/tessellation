package io.constellationnetwork.node.shared.domain.block.processing

import cats.data.EitherT

import io.constellationnetwork.node.shared.domain.block.processing.{TxChains, UsageCount}
import io.constellationnetwork.schema.Block
import io.constellationnetwork.security.signature.Signed

trait BlockAcceptanceLogic[F[_]] {
  def acceptBlock(
    block: Signed[Block],
    txChains: TxChains,
    context: BlockAcceptanceContext[F],
    contextUpdate: BlockAcceptanceContextUpdate
  ): EitherT[F, BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)]

}
