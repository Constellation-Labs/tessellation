package org.tesselation.domain.trust.storage

import org.tesselation.schema.cluster
import org.tesselation.schema.cluster.TrustInfo
import org.tesselation.schema.peer.PeerId

trait TrustStorage[F[_]] {
  def updateTrust(trustUpdates: cluster.InternalTrustUpdateBatch): F[Unit]
  def getTrust(): F[Map[PeerId, TrustInfo]]
}
