package org.tessellation.dag.snapshot

import scala.collection.immutable.SortedSet

import derevo.cats.{eqv, show}
import derevo.derive

@derive(eqv, show)
case class GlobalSnapshotTips(
  deprecated: SortedSet[DeprecatedTip],
  remainedActive: SortedSet[ActiveTip]
)
