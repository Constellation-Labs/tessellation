package org.tessellation.node.shared.domain.trust.storage

import org.tessellation.schema.SnapshotOrdinal

import derevo.cats.eqv
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(eqv, encoder, decoder)
case class OrdinalTrustMap(
  ordinal: SnapshotOrdinal,
  peerLabels: PublicTrustMap,
  trust: TrustMap
)

object OrdinalTrustMap {

  def empty: OrdinalTrustMap = OrdinalTrustMap(SnapshotOrdinal.MinValue, PublicTrustMap.empty, TrustMap.empty)

}
