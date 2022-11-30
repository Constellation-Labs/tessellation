package org.tessellation.sdk.infrastructure.consensus

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.infrastructure.consensus.declaration.kind.PeerDeclarationKind
import org.tessellation.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.derive

/** Represents various data collected from other peers
  */
@derive(eqv, show)
case class ConsensusResources[A](
  peerDeclarationsMap: Map[PeerId, PeerDeclarations],
  acksMap: Map[(PeerId, PeerDeclarationKind), Set[PeerId]],
  ackKinds: Set[PeerDeclarationKind],
  artifacts: Map[Hash, A]
)

object ConsensusResources {
  def empty[A]: ConsensusResources[A] = ConsensusResources(Map.empty, Map.empty, Set.empty, Map.empty)
}
