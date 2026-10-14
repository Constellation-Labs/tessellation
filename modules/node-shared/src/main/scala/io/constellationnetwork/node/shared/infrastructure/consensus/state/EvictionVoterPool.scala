package io.constellationnetwork.node.shared.infrastructure.consensus.state

import io.constellationnetwork.schema.peer.PeerId

/** Eviction certificates are voted by the single frozen voting committee. The target never votes on its own eviction. */
object EvictionVoterPool {

  def select(target: PeerId, committee: Set[PeerId]): Set[PeerId] = committee - target
}
