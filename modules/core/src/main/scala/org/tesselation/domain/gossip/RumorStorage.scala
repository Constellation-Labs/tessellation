package org.tesselation.domain.gossip

import org.tesselation.schema.gossip.RumorBatch
import org.tesselation.security.hash.Hash

trait RumorStorage[F[_]] {

  def addRumors(rumors: RumorBatch): F[RumorBatch]

  def getRumors(hashes: List[Hash]): F[RumorBatch]

  def getActiveHashes: F[List[Hash]]

  def getSeenHashes: F[List[Hash]]

}
