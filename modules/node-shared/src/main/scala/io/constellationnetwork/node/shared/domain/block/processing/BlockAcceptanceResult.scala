package io.constellationnetwork.node.shared.domain.block.processing

import io.constellationnetwork.schema.Block
import io.constellationnetwork.security.signature.Signed

import derevo.cats.show
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong

@derive(show)
case class BlockAcceptanceResult(
  contextUpdate: BlockAcceptanceContextUpdate,
  accepted: List[(Signed[Block], NonNegLong)],
  notAccepted: List[(Signed[Block], BlockNotAcceptedReason)]
)
