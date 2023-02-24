package org.tessellation.currency.l0.config

import org.tessellation.schema.peer.L0Peer
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.types._

object types {
  case class AppConfig(
    environment: AppEnvironment,
    http: HttpConfig,
    gossip: GossipConfig,
    healthCheck: HealthCheckConfig,
    snapshot: SnapshotConfig,
    collateral: CollateralConfig,
    globalL0Peer: L0Peer
  )
}
