package io.constellationnetwork.node.shared.domain.snapshot

import io.constellationnetwork.schema.peer.L0Peer

trait PeerSelect[F[_]] {
  def select: F[L0Peer]
}
