package io.constellationnetwork.schema

import io.constellationnetwork.ext.cats.data.OrderBasedOrdering
import io.constellationnetwork.security.signature.Signed

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
