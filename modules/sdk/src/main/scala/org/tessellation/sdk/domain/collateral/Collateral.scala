package org.tessellation.sdk.domain.collateral

import org.tessellation.schema.peer.PeerId

trait Collateral[F[_]] {
  def hasCollateral(peerId: PeerId): F[Boolean]
}
