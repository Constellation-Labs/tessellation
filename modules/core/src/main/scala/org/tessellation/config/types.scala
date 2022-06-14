package org.tessellation.config

import scala.concurrent.duration.FiniteDuration

import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.types._

import ciris.Secret
import eu.timepit.refined.types.numeric.NonNegLong
import eu.timepit.refined.types.string.NonEmptyString
import fs2.io.file.Path

object types {

  case class AppConfig(
    environment: AppEnvironment,
    http: HttpConfig,
    db: DBConfig,
    gossip: GossipConfig,
    trust: TrustConfig,
    healthCheck: HealthCheckConfig,
    snapshot: SnapshotConfig,
    collateral: CollateralConfig
  )

  case class DBConfig(
    driver: NonEmptyString,
    url: NonEmptyString,
    user: NonEmptyString,
    password: Secret[String]
  )

  case class TrustDaemonConfig(
    interval: FiniteDuration
  )

  case class TrustConfig(
    daemon: TrustDaemonConfig
  )

  case class SnapshotConfig(
    heightInterval: NonNegLong,
    globalSnapshotPath: Path,
    fallbackTriggerTimeout: FiniteDuration,
    inMemoryCapacity: NonNegLong
  )

}
