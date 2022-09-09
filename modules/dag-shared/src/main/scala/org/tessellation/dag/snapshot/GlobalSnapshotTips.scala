package org.tessellation.dag.snapshot

import scala.collection.immutable.SortedSet

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(eqv, show, encoder, decoder)
case class GlobalSnapshotTips(
  deprecated: SortedSet[DeprecatedTip],
  remainedActive: SortedSet[ActiveTip]
)
