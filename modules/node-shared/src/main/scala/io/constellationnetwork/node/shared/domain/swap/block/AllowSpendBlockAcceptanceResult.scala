package io.constellationnetwork.node.shared.domain.swap.block

import io.constellationnetwork.schema.swap.AllowSpendBlock
import io.constellationnetwork.security.signature.Signed

import derevo.cats.show
import derevo.derive
import eu.timepit.refined.cats._

@derive(show)
case class AllowSpendBlockAcceptanceResult(
  contextUpdate: AllowSpendBlockAcceptanceContextUpdate,
  accepted: List[Signed[AllowSpendBlock]],
  notAccepted: List[(Signed[AllowSpendBlock], AllowSpendBlockNotAcceptedReason)]
)
