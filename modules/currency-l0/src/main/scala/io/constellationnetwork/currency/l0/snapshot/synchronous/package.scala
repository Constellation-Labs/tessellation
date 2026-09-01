package io.constellationnetwork.currency.l0.snapshot

import io.constellationnetwork.schema.gossip.Ordinal
import io.constellationnetwork.schema.peer.PeerId

/** Currency-local copy of the release/mainnet synchronous coordination types. Nothing in this package is public snapshot schema or shared
  * L1 consensus.
  */
package object synchronous {
  type Bound = Map[PeerId, Ordinal]
}
