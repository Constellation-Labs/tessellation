package io.constellationnetwork.node.shared.domain.cluster.storage

import cats.data.Ior

import io.constellationnetwork.schema.cluster.{ClusterId, ClusterSessionToken}
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.{Peer, PeerId, PeerResponsiveness}

import com.comcast.ip4s.{Host, Port}
import fs2.Stream

trait ClusterStorage[F[_]] {
  def getPeers: F[Set[Peer]]
  def getResponsivePeers: F[Set[Peer]]
  def getPeer(id: PeerId): F[Option[Peer]]
  def addPeer(peer: Peer): F[Boolean]
  def hasPeerId(id: PeerId): F[Boolean]
  def hasPeerHostPort(host: Host, p2pPort: Port): F[Boolean]
  def updatePeerState(id: PeerId, state: NodeState): F[Boolean]
  def setPeerResponsiveness(id: PeerId, responsiveness: PeerResponsiveness): F[Unit]
  def removePeer(id: PeerId): F[Unit]
  def removePeers(ids: Set[PeerId]): F[Unit]
  def peerChanges: Stream[F, Ior[Peer, Peer]]
  def createToken: F[ClusterSessionToken]
  def getToken: F[Option[ClusterSessionToken]]
  def setToken(token: ClusterSessionToken): F[Unit]
  def getClusterId: ClusterId
}
