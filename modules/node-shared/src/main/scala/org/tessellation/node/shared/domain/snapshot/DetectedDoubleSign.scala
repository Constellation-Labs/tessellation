package org.tessellation.node.shared.domain.snapshot

import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong

@derive(eqv, show)
case class DetectedDoubleSign(
  peerId: PeerId,
  ordinal: SnapshotOrdinal,
  delta: NonNegLong,
  hashes: Seq[Hash]
)
