package org.tessellation.sdk.domain.block.processing

import org.tessellation.schema.Block
import org.tessellation.sdk.domain.block.processing.TxChains
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong

@derive(eqv, show)
case class BlockAcceptanceState[B <: Block](
  contextUpdate: BlockAcceptanceContextUpdate,
  accepted: List[(Signed[B], NonNegLong)],
  rejected: List[(Signed[B], BlockRejectionReason)],
  awaiting: List[((Signed[B], TxChains), BlockAwaitReason)]
) {

  def toBlockAcceptanceResult: BlockAcceptanceResult[B] =
    BlockAcceptanceResult(
      contextUpdate,
      accepted,
      awaiting.map { case ((block, _), reason) => (block, reason) } ++ rejected
    )
}

object BlockAcceptanceState {

  def withRejectedBlocks[B <: Block](rejected: List[(Signed[B], BlockRejectionReason)]): BlockAcceptanceState[B] =
    BlockAcceptanceState(
      contextUpdate = BlockAcceptanceContextUpdate.empty,
      accepted = List.empty,
      rejected = rejected,
      awaiting = List.empty
    )
}
