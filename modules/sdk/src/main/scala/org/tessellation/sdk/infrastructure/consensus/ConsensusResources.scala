package org.tessellation.sdk.infrastructure.consensus

import org.tessellation.schema.peer.PeerId
import org.tessellation.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.derive

@derive(eqv, show)
case class ConsensusResources[A](
  peerDeclarationsMap: Map[PeerId, PeerDeclarations],
  artifacts: Map[Hash, A],
  proposedFacilitators: Set[PeerId],
  removedFacilitators: Set[PeerId]
)

object ConsensusResources {
  def empty[A]: ConsensusResources[A] = ConsensusResources(Map.empty, Map.empty, Set.empty, Set.empty)
}
