package org.tessellation.dag.block.processing

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong

@derive(eqv, show)
case class BlockAcceptanceState(
  contextUpdate: BlockAcceptanceContextUpdate,
  accepted: List[(Signed[DAGBlock], NonNegLong)],
  rejected: List[(Signed[DAGBlock], BlockRejectionReason)],
  awaiting: List[(Signed[DAGBlock], BlockAwaitReason)]
) {

  def toBlockAcceptanceResult: BlockAcceptanceResult =
    BlockAcceptanceResult(
      contextUpdate,
      accepted,
      awaiting ++ rejected
    )
}

object BlockAcceptanceState {

  val empty: BlockAcceptanceState = BlockAcceptanceState(
    BlockAcceptanceContextUpdate.empty,
    List.empty,
    List.empty,
    List.empty
  )
}
