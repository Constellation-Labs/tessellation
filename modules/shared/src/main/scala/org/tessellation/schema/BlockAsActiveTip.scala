package org.tessellation.schema

import org.tessellation.ext.cats.data.OrderBasedOrdering
import org.tessellation.security.signature.Signed

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.types.numeric.NonNegLong

@derive(show, encoder, decoder, order)
case class BlockAsActiveTip(
  block: Signed[Block],
  usageCount: NonNegLong
)

object BlockAsActiveTip {
  implicit object OrderingInstance extends OrderBasedOrdering[BlockAsActiveTip]
}
