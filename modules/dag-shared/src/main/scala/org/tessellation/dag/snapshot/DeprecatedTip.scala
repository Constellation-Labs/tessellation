package org.tessellation.dag.snapshot

import org.tessellation.dag.domain.block.BlockReference
import org.tessellation.ext.cats.data.OrderBasedOrdering

import derevo.cats.{order, show}
import derevo.derive

@derive(order, show)
case class DeprecatedTip(
  block: BlockReference,
  deprecatedAt: SnapshotOrdinal
)

object DeprecatedTip {
  implicit object OrderingInstance extends OrderBasedOrdering[DeprecatedTip]
}
