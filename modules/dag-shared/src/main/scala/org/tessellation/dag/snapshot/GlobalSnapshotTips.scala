package org.tessellation.dag.snapshot

import derevo.cats.{eqv, show}
import derevo.derive

@derive(eqv, show)
case class GlobalSnapshotTips(
  deprecated: Set[DeprecatedTip],
  remainedActive: Set[ActiveTip]
)
