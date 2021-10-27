package org.tesselation.domain.trust.storage

import org.tesselation.schema.peer.PeerId
import org.tesselation.schema.trust.{InternalTrustUpdateBatch, PublicTrust, TrustInfo}

trait TrustStorage[F[_]] {
  def updateTrust(trustUpdates: InternalTrustUpdateBatch): F[Unit]
  def getTrust: F[Map[PeerId, TrustInfo]]
  def getPublicTrust: F[PublicTrust]
}
