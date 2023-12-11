package org.tessellation.currency.l0.config

import org.tessellation.env.AppEnvironment
import org.tessellation.node.shared.config.types._
import org.tessellation.schema.peer.L0Peer

object types {
  case class AppConfig(
    environment: AppEnvironment,
    peerDiscoveryDelay: PeerDiscoveryDelay,
    http: HttpConfig,
    gossip: GossipConfig,
    healthCheck: HealthCheckConfig,
    snapshot: SnapshotConfig,
    collateral: CollateralConfig,
    globalL0Peer: L0Peer,
    snapshotSizeConfig: SnapshotSizeConfig,
    doubleSignDetectDaemon: DoubleSignDetectDaemonConfig
  )
}
