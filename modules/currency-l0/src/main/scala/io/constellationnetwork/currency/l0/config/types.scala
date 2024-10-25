package io.constellationnetwork.currency.l0.config

import io.constellationnetwork.node.shared.config.types._
import io.constellationnetwork.schema.peer.L0Peer

import eu.timepit.refined.types.numeric.PosInt

object types {
  case class AppConfigReader(
    peerDiscovery: PeerDiscoveryConfig,
    snapshot: SnapshotConfig
  )

  case class AppConfig(
    peerDiscovery: PeerDiscoveryConfig,
    snapshot: SnapshotConfig,
    snapshotConfirmation: SnapshotConfirmationConfig,
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

  case class SnapshotConfirmationConfig(
    fixedWindowSize: PosInt
  )
}
