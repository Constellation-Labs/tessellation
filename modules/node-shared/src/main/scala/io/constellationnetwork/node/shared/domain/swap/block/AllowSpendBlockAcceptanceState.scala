package io.constellationnetwork.node.shared.domain.swap.block

import io.constellationnetwork.node.shared.domain.swap.AllowSpendChainValidator.AllowSpendNel
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.swap.AllowSpendBlock
import io.constellationnetwork.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.cats._

@derive(eqv, show)
case class AllowSpendBlockAcceptanceState(
  contextUpdate: AllowSpendBlockAcceptanceContextUpdate,
  accepted: List[Signed[AllowSpendBlock]],
  rejected: List[(Signed[AllowSpendBlock], AllowSpendBlockRejectionReason)],
  awaiting: List[((Signed[AllowSpendBlock], Map[Address, AllowSpendNel]), AllowSpendBlockAwaitReason)]
) {

  def toBlockAcceptanceResult: AllowSpendBlockAcceptanceResult =
    AllowSpendBlockAcceptanceResult(
      contextUpdate,
      accepted,
      awaiting.map { case ((block, _), reason) => (block, reason) } ++ rejected
    )
}

object AllowSpendBlockAcceptanceState {

  def withRejectedBlocks(rejected: List[(Signed[AllowSpendBlock], AllowSpendBlockRejectionReason)]): AllowSpendBlockAcceptanceState =
    AllowSpendBlockAcceptanceState(
      contextUpdate = AllowSpendBlockAcceptanceContextUpdate.empty,
      accepted = List.empty,
      rejected = rejected,
      awaiting = List.empty
    )
}
