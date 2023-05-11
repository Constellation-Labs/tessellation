package org.tessellation.sdk.domain.snapshot

import org.tessellation.schema.peer.Peer

trait PeerSelect[F[_]] {
  def select: F[Peer]
}

object PeerSelect {
  val peerSelectLoggerName = "PeerSelectLogger"
}
