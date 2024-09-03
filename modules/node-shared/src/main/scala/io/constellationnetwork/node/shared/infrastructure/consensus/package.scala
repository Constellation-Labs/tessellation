package io.constellationnetwork.node.shared.infrastructure

import io.constellationnetwork.schema.gossip.Ordinal
import io.constellationnetwork.schema.peer.PeerId

package object consensus {
  type Bound = Map[PeerId, Ordinal]
}
