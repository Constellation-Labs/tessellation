package org.tessellation.dag.block.processing

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong

@derive(eqv, show)
case class BlockAcceptanceResult(
  contextUpdate: BlockAcceptanceContextUpdate,
  accepted: List[(Signed[DAGBlock], NonNegLong)],
  notAccepted: List[(Signed[DAGBlock], BlockNotAcceptedReason)]
)
