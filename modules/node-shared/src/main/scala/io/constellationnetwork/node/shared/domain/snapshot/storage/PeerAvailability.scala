package io.constellationnetwork.node.shared.domain.snapshot.storage

import io.constellationnetwork.schema.peer.Peer

trait PeerAvailability[F[_]] {
  def recordSuccess(peer: Peer): F[Unit]
  def recordFailure(peer: Peer): F[Unit]
  def sortByAvailability(peers: List[Peer]): F[List[Peer]]
}
