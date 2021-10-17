package org.tesselation.domain.gossip

import org.tesselation.crypto.hash.Hash
import org.tesselation.schema.gossip.RumorBatch

trait RumorStorage[F[_]] {

  def addRumors(rumors: RumorBatch): F[RumorBatch]

  def getRumors(hashes: List[Hash]): F[RumorBatch]

  def getActiveHashes: F[List[Hash]]

  def getSeenHashes: F[List[Hash]]

}
