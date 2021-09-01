package org.tesselation.domain.cluster

import org.tesselation.schema.peer.{Peer, PeerId}

import com.comcast.ip4s.{Host, Port}

trait ClusterStorage[F[_]] {
  def getPeers: F[Set[Peer]]
  def addPeer(peer: Peer): F[Unit]
  def hasPeerId(id: PeerId): F[Boolean]
  def hasPeerHostPort(host: Host, p2pPort: Port): F[Boolean]
}
