package org.tessellation.dag.snapshot

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.ext.cats.data.OrderBasedOrdering
import org.tessellation.schema._
import org.tessellation.security.signature.Signed

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.types.numeric.NonNegLong

@derive(order, show, encoder, decoder)
case class BlockAsActiveTip(
  block: Signed[DAGBlock],
  usageCount: NonNegLong
)

object BlockAsActiveTip {
  implicit object OrderingInstance extends OrderBasedOrdering[BlockAsActiveTip]
}
