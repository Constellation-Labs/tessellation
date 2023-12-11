package org.tessellation.node.shared.cli

import cats.data.NonEmptySet
import cats.syntax.eq._

import scala.concurrent.duration._

import org.tessellation.env.AppEnvironment
import org.tessellation.env.AppEnvironment.Mainnet
import org.tessellation.env.env._
import org.tessellation.node.shared.PriorityPeerIds
import org.tessellation.node.shared.config.types._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.PeerId

import eu.timepit.refined.auto._
import eu.timepit.refined.types.all.PosLong
import fs2.io.file.Path

object CliMethod {
  val snapshotSizeConfig: SnapshotSizeConfig = SnapshotSizeConfig(
    singleSignatureSizeInBytes = 296L,
    maxStateChannelSnapshotBinarySizeInBytes = PosLong(512_000L)
  )

  val collateralConfig = (environment: AppEnvironment, amount: Option[Amount]) =>
    CollateralConfig(
      amount = amount
        .filter(_ => environment =!= Mainnet)
        .getOrElse(Amount(250_000_00000000L))
    )

  val gossipConfig: GossipConfig = GossipConfig(
    storage = RumorStorageConfig(
      peerRumorsCapacity = 50L,
      activeCommonRumorsCapacity = 20L,
      seenCommonRumorsCapacity = 50L
    ),
    daemon = GossipDaemonConfig(
      peerRound = GossipRoundConfig(
        fanout = 1,
        interval = 0.2.seconds,
        maxConcurrentRounds = 4
      ),
      commonRound = GossipRoundConfig(
        fanout = 1,
        interval = 0.5.seconds,
        maxConcurrentRounds = 2
      )
    )
  )

  val trustStorageConfig: TrustStorageConfig = TrustStorageConfig(
    ordinalTrustUpdateInterval = 1000L,
    ordinalTrustUpdateDelay = 500L,
    seedlistInputBias = 0.7,
    seedlistOutputBias = 0.5
  )

  val forkInfoStorageConfig: ForkInfoStorageConfig = ForkInfoStorageConfig(10)

  val doubleSignDetectConfig: DoubleSignDetectConfig = DoubleSignDetectConfig(
    maxDetectionDelta = 10L
  )

  val leavingDelay = 30.seconds

  def healthCheckConfig(pingEnabled: Boolean) = HealthCheckConfig(
    removeUnresponsiveParallelPeersAfter = 10.seconds,
    requestProposalsAfter = 8.seconds,
    ping = PingHealthCheckConfig(
      enabled = pingEnabled,
      concurrentChecks = 3,
      defaultCheckTimeout = 6.seconds,
      defaultCheckAttempts = 3,
      ensureCheckInterval = 10.seconds
    )
  )
}

trait CliMethod {

  val keyStore: StorePath
  val alias: KeyAlias
  val password: Password

  val environment: AppEnvironment

  val seedlistPath: Option[SeedListPath]

  val l0SeedlistPath: Option[SeedListPath]

  val prioritySeedlistPath: Option[SeedListPath]

  val stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]]

  val trustRatingsPath: Option[Path]

  val httpConfig: HttpConfig

  val stateAfterJoining: NodeState

  val collateralAmount: Option[Amount]

  def healthCheckConfig(pingEnabled: Boolean): HealthCheckConfig = CliMethod.healthCheckConfig(pingEnabled)

  val gossipConfig: GossipConfig = CliMethod.gossipConfig

  val leavingDelay: FiniteDuration = CliMethod.leavingDelay

  val collateralConfig: (AppEnvironment, Option[Amount]) => CollateralConfig = CliMethod.collateralConfig

  val snapshotSizeConfig: SnapshotSizeConfig = CliMethod.snapshotSizeConfig

  val trustStorageConfig: TrustStorageConfig = CliMethod.trustStorageConfig

  val forkInfoStorageConfig: ForkInfoStorageConfig = CliMethod.forkInfoStorageConfig

  val doubleSignDetectConfig: DoubleSignDetectConfig = CliMethod.doubleSignDetectConfig

  lazy val nodeSharedConfig: SharedConfig = SharedConfig(
    environment,
    gossipConfig,
    httpConfig,
    leavingDelay,
    stateAfterJoining,
    collateralConfig(environment, collateralAmount),
    trustStorageConfig,
    PriorityPeerIds.get(environment),
    snapshotSizeConfig,
    forkInfoStorageConfig,
    doubleSignDetectConfig
  )

}
