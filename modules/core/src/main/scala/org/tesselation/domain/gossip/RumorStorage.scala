package org.tesselation.domain.gossip

import org.tesselation.schema.gossip.{Rumor, RumorBatch}
import org.tesselation.schema.peer.PeerId
import org.tesselation.security.hash.Hash

import eu.timepit.refined.types.numeric.PosLong

trait RumorStorage[F[_]] {

  def addRumors(rumors: RumorBatch): F[RumorBatch]

  def getRumors(hashes: List[Hash]): F[RumorBatch]

  def getActiveHashes: F[List[Hash]]

  def getSeenHashes: F[List[Hash]]

  def tryGetAndUpdateCounter(rumor: Rumor): F[Option[PosLong]]

  def resetCounter(peer: PeerId): F[Unit]

}
