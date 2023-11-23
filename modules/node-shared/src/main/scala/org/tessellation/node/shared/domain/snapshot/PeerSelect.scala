package org.tessellation.node.shared.domain.snapshot

import org.tessellation.schema.peer.L0Peer

trait PeerSelect[F[_]] {
  def select: F[L0Peer]
}
