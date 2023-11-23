package org.tessellation.node.shared.domain.fork

import org.tessellation.schema.peer.PeerId

trait ForkInfoStorage[F[_]] {
  def add(peerId: PeerId, entry: ForkInfo): F[Unit]
  def getForkInfo: F[ForkInfoMap]
}
