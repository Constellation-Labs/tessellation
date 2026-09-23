package io.constellationnetwork.node.shared.domain.cluster.storage

import cats.Monad
import cats.data.Ior
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.schema.cluster.{ClusterId, ClusterSessionToken, SessionToken}
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

  /** Session-conditional removal (compare-and-set on the recorded session): remove the peer only if the record still carries
    * `expectedSession`, so a stale health/session observation can never discard a newer session that was installed concurrently. Returns
    * whether a record was removed. The default is a read-then-remove for implementations without an atomic record store; the infrastructure
    * implementation overrides it with a single atomic update.
    */
  def removePeerIfSession(id: PeerId, expectedSession: SessionToken)(implicit F: Monad[F]): F[Boolean] =
    getPeer(id).flatMap {
      case Some(peer) if peer.session === expectedSession => removePeer(id).as(true)
      case _                                              => F.pure(false)
    }

  /** Session-conditional responsiveness update (compare-and-set on the recorded session): relabel the peer only if the record still carries
    * `expectedSession`, so an answer obtained for one session can never relabel a record that was replaced while the request was in flight.
    * Returns whether the record was bound to `expectedSession` (and therefore carries `responsiveness` afterwards). The default is a
    * read-then-set for implementations without an atomic record store; the infrastructure implementation overrides it with a single atomic
    * update.
    */
  def setPeerResponsivenessIfSession(id: PeerId, expectedSession: SessionToken, responsiveness: PeerResponsiveness)(
    implicit F: Monad[F]
  ): F[Boolean] =
    getPeer(id).flatMap {
      case Some(peer) if peer.session === expectedSession => setPeerResponsiveness(id, responsiveness).as(true)
      case _                                              => F.pure(false)
    }
  def peerChanges: Stream[F, Ior[Peer, Peer]]
  def createToken: F[ClusterSessionToken]
  def getToken: F[Option[ClusterSessionToken]]
  def setToken(token: ClusterSessionToken): F[Unit]
  def getClusterId: ClusterId
}
