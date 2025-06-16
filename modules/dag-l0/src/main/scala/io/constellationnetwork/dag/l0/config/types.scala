package io.constellationnetwork.dag.l0.config

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types._
import io.constellationnetwork.schema.SnapshotOrdinal

import ciris.Secret
import eu.timepit.refined.types.numeric._
import eu.timepit.refined.types.string.NonEmptyString

object types {
  case class AppConfigReader(
    trust: TrustConfig,
    snapshot: SnapshotConfig,
    stateChannel: StateChannelConfig,
    peerDiscovery: PeerDiscoveryConfig,
    incremental: IncrementalConfig
  )

  case class AppConfig(
    trust: TrustConfig,
    snapshot: SnapshotConfig,
    rewards: ClassicRewardsConfig,
    stateChannel: StateChannelConfig,
    peerDiscovery: PeerDiscoveryConfig,
    incremental: IncrementalConfig,
    shared: SharedConfig
  ) {
    val environment = shared.environment
    val gossip = shared.gossip
    val http = shared.http
    val snapshotSize = shared.snapshotSize
    val collateral = shared.collateral
  }

  case class IncrementalConfig(
    lastFullGlobalSnapshotOrdinal: Map[AppEnvironment, SnapshotOrdinal]
  )

  case class StateChannelConfig(
    pullDelay: NonNegLong,
    purgeDelay: NonNegLong
  )

  case class PeerDiscoveryConfig(
    delay: PeerDiscoveryDelay
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
}
