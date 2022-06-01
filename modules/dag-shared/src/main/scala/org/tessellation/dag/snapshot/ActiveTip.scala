package org.tessellation.dag.snapshot

import org.tessellation.dag.domain.block.BlockReference
import org.tessellation.ext.cats.data.OrderBasedOrdering

import derevo.cats.{order, show}
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong

@derive(order, show)
case class ActiveTip(
  block: BlockReference,
  usageCount: NonNegLong,
  introducedAt: SnapshotOrdinal
)

object ActiveTip {
  implicit object OrderingInstance extends OrderBasedOrdering[ActiveTip]
}
