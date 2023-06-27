package org.tessellation.sdk.domain.trust.storage

import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust._

trait TrustStorage[F[_]] {
  def updateTrust(trustUpdates: PeerObservationAdjustmentUpdateBatch): F[Unit]
  def updatePredictedTrust(trustUpdates: Map[PeerId, Double]): F[Unit]

  def getTrust: F[TrustMap]
  def getCurrentOrdinalTrust: F[OrdinalTrustMap]
  def getNextOrdinalTrust: F[OrdinalTrustMap]
  def getPublicTrust: F[PublicTrust]
  def updatePeerPublicTrustInfo(peerId: PeerId, publicTrust: PublicTrust): F[Unit]
  def updateNext(ordinal: SnapshotOrdinal): F[Option[SnapshotOrdinalPublicTrust]]
  def updateNext(peerId: PeerId, publicTrust: SnapshotOrdinalPublicTrust): F[Unit]
  def updateCurrent(ordinal: SnapshotOrdinal): F[Unit]
}
