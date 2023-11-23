package org.tessellation.node.shared.infrastructure.consensus

import org.tessellation.node.shared.infrastructure.consensus.declaration.kind.PeerDeclarationKind
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.derive

/** Represents various data collected from other peers
  */
@derive(eqv, show)
case class ConsensusResources[A](
  peerDeclarationsMap: Map[PeerId, PeerDeclarations],
  acksMap: Map[(PeerId, PeerDeclarationKind), Set[PeerId]],
  withdrawalsMap: Map[PeerId, PeerDeclarationKind],
  ackKinds: Set[PeerDeclarationKind],
  artifacts: Map[Hash, A]
)

object ConsensusResources {
  def empty[A]: ConsensusResources[A] = ConsensusResources(Map.empty, Map.empty, Map.empty, Set.empty, Map.empty)
}
