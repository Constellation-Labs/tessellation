package org.tessellation.domain.trust.storage

import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust.{SnapshotOrdinalPublicTrust, SnapshotOrdinalTrustInfo, TrustInfo}

trait SnapshotOrdinalTrustStorage[F[_]] {
  def getTrust: F[Map[PeerId, SnapshotOrdinalTrustInfo]]
  def getSnapshotOrdinalPublicTrust: F[SnapshotOrdinalPublicTrust]
  def update(updates: Map[PeerId, TrustInfo]): F[SnapshotOrdinal]
}
