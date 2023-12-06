package org.tessellation.node.shared.domain.block.processing

import org.tessellation.node.shared.domain.block.processing.TxChains
import org.tessellation.schema.Block
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong

@derive(eqv, show)
case class BlockAcceptanceState(
  contextUpdate: BlockAcceptanceContextUpdate,
  accepted: List[(Signed[Block], NonNegLong)],
  rejected: List[(Signed[Block], BlockRejectionReason)],
  awaiting: List[((Signed[Block], TxChains), BlockAwaitReason)]
) {

  def toBlockAcceptanceResult: BlockAcceptanceResult =
    BlockAcceptanceResult(
      contextUpdate,
      accepted,
      awaiting.map { case ((block, _), reason) => (block, reason) } ++ rejected
    )
}

object BlockAcceptanceState {

  def withRejectedBlocks(rejected: List[(Signed[Block], BlockRejectionReason)]): BlockAcceptanceState =
    BlockAcceptanceState(
      contextUpdate = BlockAcceptanceContextUpdate.empty,
      accepted = List.empty,
      rejected = rejected,
      awaiting = List.empty
    )
}
