package org.tesselation.domain.cluster

import org.tesselation.schema.cluster.PeerToJoin

trait Cluster[F[_]] {
  def join(toPeer: PeerToJoin): F[Unit]
}
