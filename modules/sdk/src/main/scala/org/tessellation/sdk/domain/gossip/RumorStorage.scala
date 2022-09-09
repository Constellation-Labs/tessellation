package org.tessellation.sdk.domain.gossip

import org.tessellation.schema.gossip.RumorRaw
import org.tessellation.security.Hashed
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

trait RumorStorage[F[_]] {

  def getRumors(hashes: List[Hash]): F[List[Signed[RumorRaw]]]

  def addRumorIfNotSeen(hashedRumor: Hashed[RumorRaw]): F[Boolean]

  def getRumor(hash: Hash): F[Option[Signed[RumorRaw]]]

  def getActiveHashes: F[List[Hash]]

  def getSeenHashes: F[List[Hash]]

}
