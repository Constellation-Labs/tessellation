package org.tessellation.node.shared.domain.block.processing

import org.tessellation.schema.Block
import org.tessellation.security.signature.Signed

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
