package org.tessellation.sdk.domain.gossip

import org.tessellation.schema.gossip.{Rumor, RumorBatch}
import org.tessellation.security.hash.Hash

trait RumorStorage[F[_]] {

  def addRumors(rumors: RumorBatch): F[RumorBatch]

  def getRumors(hashes: List[Hash]): F[RumorBatch]

  def getActiveHashes: F[List[Hash]]

  def getSeenHashes: F[List[Hash]]

  def tryUpdateOrdinal(rumor: Rumor): F[Boolean]

}
