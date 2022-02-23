package org.tessellation.sdk.infrastructure

import org.tessellation.schema.gossip.Ordinal
import org.tessellation.schema.peer.PeerId

package object consensus {
  type Bound = Map[PeerId, Ordinal]
}
