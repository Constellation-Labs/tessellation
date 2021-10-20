package org.tesselation.domain.cluster.storage

import org.tesselation.schema.cluster
import org.tesselation.schema.cluster.TrustInfo
import org.tesselation.schema.peer.{Peer, PeerId}

import com.comcast.ip4s.{Host, Port}

trait ClusterStorage[F[_]] {
  def updateTrust(trustUpdates: cluster.InternalTrustUpdateBatch): F[Unit]
  def getTrust(): F[Map[PeerId, TrustInfo]]
  def getPeers: F[Set[Peer]]
  def addPeer(peer: Peer): F[Unit]
  def hasPeerId(id: PeerId): F[Boolean]
  def hasPeerHostPort(host: Host, p2pPort: Port): F[Boolean]
}
