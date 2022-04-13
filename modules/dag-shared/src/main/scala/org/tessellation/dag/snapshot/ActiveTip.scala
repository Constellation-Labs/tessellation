package org.tessellation.dag.snapshot

import org.tessellation.dag.domain.block.BlockReference

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong

@derive(eqv, show)
case class ActiveTip(
  block: BlockReference,
  usageCount: NonNegLong,
  introducedAt: SnapshotOrdinal
)
