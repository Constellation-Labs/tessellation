package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.derive

/** Represents various data collected from other peers
  */
@derive(eqv, show)
case class ConsensusResources[A, Kind](
  peerDeclarationsMap: Map[PeerId, PeerDeclarations],
  acksMap: Map[(PeerId, Kind), Set[PeerId]],
  withdrawalsMap: Map[PeerId, Kind],
  ackKinds: Set[Kind],
  artifacts: Map[Hash, A]
)

object ConsensusResources {
  def empty[A, Kind]: ConsensusResources[A, Kind] = ConsensusResources(Map.empty, Map.empty, Map.empty, Set.empty, Map.empty)
}
