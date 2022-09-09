package org.tessellation.dag.snapshot

import org.tessellation.dag.domain.block.BlockReference
import org.tessellation.ext.cats.data.OrderBasedOrdering
import org.tessellation.schema._

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.types.numeric.NonNegLong

@derive(order, show, encoder, decoder)
case class ActiveTip(
  block: BlockReference,
  usageCount: NonNegLong,
  introducedAt: SnapshotOrdinal
)

object ActiveTip {
  implicit object OrderingInstance extends OrderBasedOrdering[ActiveTip]
}
