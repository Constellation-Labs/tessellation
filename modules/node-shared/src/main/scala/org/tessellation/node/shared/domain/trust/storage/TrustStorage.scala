package org.tessellation.node.shared.domain.trust.storage

import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust._

trait TrustStorage[F[_]] {
  def updateTrust(trustUpdates: PeerObservationAdjustmentUpdateBatch): F[Unit]
  def updateTrustWithBiases(selfPeerId: PeerId): F[Unit]

  def getTrust: F[TrustMap]
  def getBiasedTrustScores: F[TrustScores]
  def updatePeerPublicTrustInfo(peerId: PeerId, publicTrust: PublicTrust): F[Unit]

  def getCurrentOrdinalTrust: F[OrdinalTrustMap]
  def updateCurrent(ordinal: SnapshotOrdinal): F[Unit]
  def getNextOrdinalTrust: F[OrdinalTrustMap]
  def updateNext(ordinal: SnapshotOrdinal): F[Option[SnapshotOrdinalPublicTrust]]
  def updateNext(peerId: PeerId, publicTrust: SnapshotOrdinalPublicTrust): F[Unit]

  def getPublicTrust: F[PublicTrust]
}
