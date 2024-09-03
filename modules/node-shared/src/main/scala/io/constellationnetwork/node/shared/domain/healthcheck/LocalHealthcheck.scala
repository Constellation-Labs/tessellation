package io.constellationnetwork.node.shared.domain.healthcheck

import io.constellationnetwork.schema.peer.{Peer, PeerId}

trait LocalHealthcheck[F[_]] {
  def start(peer: Peer): F[Unit]
  def cancel(peerId: PeerId): F[Unit]
}
