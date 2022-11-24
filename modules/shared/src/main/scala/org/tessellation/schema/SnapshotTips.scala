package org.tessellation.schema

import scala.collection.immutable.SortedSet

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(eqv, show, encoder, decoder)
case class SnapshotTips(
  deprecated: SortedSet[DeprecatedTip],
  remainedActive: SortedSet[ActiveTip]
)
