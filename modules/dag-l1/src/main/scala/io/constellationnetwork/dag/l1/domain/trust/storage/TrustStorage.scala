package io.constellationnetwork.dag.l1.domain.trust.storage

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.trust._

trait TrustStorage[F[_]] {
  def getScore(peerId: PeerId): F[Option[Score]]
  def getRating(peerId: PeerId): F[Option[Rating]]
  def getObservationAdjustment(peerId: PeerId): F[Option[ObservationAdjustment]]
  def updateTrustValues(trustValues: Map[PeerId, TrustDbValues]): F[Unit]
  def clean: F[Unit]
}
