package org.tessellation.currency.l0.config

import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.types._

import eu.timepit.refined.types.numeric.NonNegLong
import fs2.io.file.Path

object types {
  case class AppConfig(
    environment: AppEnvironment,
    http: HttpConfig,
    gossip: GossipConfig,
    healthCheck: HealthCheckConfig,
    snapshot: SnapshotConfig,
    collateral: CollateralConfig
  )

  case class SnapshotConfig(
    heightInterval: NonNegLong,
    snapshotPath: Path,
    inMemoryCapacity: NonNegLong
  )
}
