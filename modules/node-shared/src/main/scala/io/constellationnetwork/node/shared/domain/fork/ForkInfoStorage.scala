package io.constellationnetwork.node.shared.domain.fork

import io.constellationnetwork.schema.peer.PeerId

trait ForkInfoStorage[F[_]] {
  def add(peerId: PeerId, entry: ForkInfo): F[Unit]
  def getForkInfo: F[ForkInfoMap]
}
