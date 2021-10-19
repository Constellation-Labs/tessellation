package org.tesselation.domain.cluster.storage

import org.tesselation.schema.node.NodeState
import org.tesselation.schema.peer.{Peer, PeerId}

import com.comcast.ip4s.{Host, Port}

trait ClusterStorage[F[_]] {
  def getPeers: F[Set[Peer]]
  def addPeer(peer: Peer): F[Unit]
  def hasPeerId(id: PeerId): F[Boolean]
  def hasPeerHostPort(host: Host, p2pPort: Port): F[Boolean]
  def setPeerState(id: PeerId, state: NodeState): F[Unit]
}
