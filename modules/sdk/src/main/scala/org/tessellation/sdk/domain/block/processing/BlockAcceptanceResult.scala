package org.tessellation.sdk.domain.block.processing

import org.tessellation.schema.Block
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong

@derive(eqv, show)
case class BlockAcceptanceResult[B <: Block[_]](
  contextUpdate: BlockAcceptanceContextUpdate,
  accepted: List[(Signed[B], NonNegLong)],
  notAccepted: List[(Signed[B], BlockNotAcceptedReason)]
)
