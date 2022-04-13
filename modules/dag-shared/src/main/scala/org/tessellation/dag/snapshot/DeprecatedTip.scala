package org.tessellation.dag.snapshot

import org.tessellation.dag.domain.block.BlockReference

import derevo.cats.{eqv, show}
import derevo.derive

@derive(eqv, show)
case class DeprecatedTip(
  block: BlockReference,
  deprecatedAt: SnapshotOrdinal
)
