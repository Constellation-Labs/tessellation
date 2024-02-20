package org.tessellation.currency.l0.config

import org.tessellation.node.shared.config.types._
import org.tessellation.schema.peer.L0Peer

object types {
  case class AppConfigReader(
    peerDiscovery: PeerDiscoveryConfig,
    snapshot: SnapshotConfig
  )

  case class AppConfig(
    peerDiscovery: PeerDiscoveryConfig,
    snapshot: SnapshotConfig,
    globalL0Peer: L0Peer,
    shared: SharedConfig
  ) {
    val environment = shared.environment
    val gossip = shared.gossip
    val http = shared.http
    val snapshotSize = shared.snapshotSize
    val collateral = shared.collateral
  }

  case class PeerDiscoveryConfig(
    delay: PeerDiscoveryDelay
  )
}
