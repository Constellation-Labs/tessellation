package org.tessellation.sdk.domain.cluster.storage

import org.tessellation.schema.cluster.{ClusterId, ClusterSessionToken}
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.{Peer, PeerId}

import com.comcast.ip4s.{Host, Port}
import fs2.Stream

trait ClusterStorage[F[_]] {
  def getPeers: F[Set[Peer]]
  def getPeer(id: PeerId): F[Option[Peer]]
  def addPeer(peer: Peer): F[Unit]
  def hasPeerId(id: PeerId): F[Boolean]
  def hasPeerHostPort(host: Host, p2pPort: Port): F[Boolean]
  def setPeerState(id: PeerId, state: NodeState): F[Unit]
  def removePeer(id: PeerId): F[Unit]
  def peerChanges: Stream[F, (PeerId, Option[Peer])]
  def setClusterSession(token: ClusterSessionToken): F[Unit]
  def getClusterSession: F[Option[ClusterSessionToken]]
  def getClusterId: ClusterId
}
