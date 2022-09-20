package org.tessellation.sdk.domain.healthcheck

import org.tessellation.schema.peer.{Peer, PeerId}

trait LocalHealthcheck[F[_]] {
  def start(peer: Peer): F[Unit]
  def cancel(peerId: PeerId): F[Unit]
}
