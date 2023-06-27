package org.tessellation.sdk.domain.trust.storage

import org.tessellation.schema.SnapshotOrdinal

import derevo.cats.eqv
import derevo.circe.magnolia.encoder
import derevo.derive

@derive(eqv, encoder)
case class OrdinalTrustMap(
  ordinal: SnapshotOrdinal,
  peerLabels: PublicTrustMap,
  trust: TrustMap
)

object OrdinalTrustMap {

  def empty: OrdinalTrustMap = OrdinalTrustMap(SnapshotOrdinal.MinValue, PublicTrustMap.empty, TrustMap.empty)

}
