package org.tessellation.sdk.domain.cluster.storage

import cats.data.NonEmptySet

import org.tessellation.schema.peer.{L0Peer, PeerId}

trait L0ClusterStorage[F[_]] {
  def getPeers: F[NonEmptySet[L0Peer]]
  def getPeer(id: PeerId): F[Option[L0Peer]]
  def getRandomPeer: F[L0Peer]
  def addPeers(l0Peers: Set[L0Peer]): F[Unit]
  def setPeers(l0Peers: NonEmptySet[L0Peer]): F[Unit]
}
