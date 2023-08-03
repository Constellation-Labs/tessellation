package org.tessellation.schema

import cats.Order

import org.tessellation.security.signature.Signed

import derevo.cats.show
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.types.numeric.NonNegLong

@derive(show, encoder, decoder)
case class BlockAsActiveTip[B <: Block](
  block: Signed[B],
  usageCount: NonNegLong
)

object BlockAsActiveTip {
  implicit def order[B <: Block: Order]: Order[BlockAsActiveTip[B]] = Order.whenEqual(
    Order.by[BlockAsActiveTip[B], Signed[B]](_.block),
    Order.by[BlockAsActiveTip[B], NonNegLong](_.usageCount)
  )
}
