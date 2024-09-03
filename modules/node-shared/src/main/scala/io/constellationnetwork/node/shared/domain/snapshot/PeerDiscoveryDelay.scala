package io.constellationnetwork.node.shared.domain.snapshot

trait PeerDiscoveryDelay[F[_]] {

  def waitForPeers: F[Unit]

}
