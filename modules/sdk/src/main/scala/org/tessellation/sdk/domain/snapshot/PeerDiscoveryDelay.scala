package org.tessellation.sdk.domain.snapshot

trait PeerDiscoveryDelay[F[_]] {

  def waitForPeers: F[Unit]

}
