package org.tessellation.dag.snapshot

import org.tessellation.dag.domain.block.BlockReference
import org.tessellation.ext.cats.data.OrderBasedOrdering

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(order, show, encoder, decoder)
case class DeprecatedTip(
  block: BlockReference,
  deprecatedAt: SnapshotOrdinal
)

object DeprecatedTip {
  implicit object OrderingInstance extends OrderBasedOrdering[DeprecatedTip]
}
