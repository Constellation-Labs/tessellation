package org.tessellation.sdk.cli

import scala.concurrent.duration._

import org.tessellation.cli.env._
import org.tessellation.schema.node.NodeState
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.types._

import eu.timepit.refined.auto.autoRefineV
import fs2.io.file.Path

trait CliMethod {

  val keyStore: StorePath
  val alias: KeyAlias
  val password: Password

  val environment: AppEnvironment

  val whitelistingPath: Option[Path]

  val httpConfig: HttpConfig

  val stateAfterJoining: NodeState

  val gossipConfig: GossipConfig = GossipConfig(
    storage = RumorStorageConfig(
      activeRetention = 2.seconds,
      seenRetention = 2.minutes
    ),
    daemon = GossipDaemonConfig(
      fanout = 2,
      interval = 0.2.seconds,
      maxConcurrentHandlers = 20
    )
  )

  val leavingDelay = 30.seconds

  val healthCheckConfig = HealthCheckConfig(
    removeUnresponsiveParallelPeersAfter = 10.seconds,
    ping = PingHealthCheckConfig(
      concurrentChecks = 3,
      defaultCheckTimeout = 10.seconds,
      defaultCheckAttempts = 3,
      ensureCheckInterval = 10.seconds
    ),
    peerDeclaration = PeerDeclarationHealthCheckConfig(
      receiveTimeout = 20.seconds,
      triggerInterval = 10.seconds
    )
  )

  lazy val sdkConfig: SdkConfig = SdkConfig(
    environment,
    gossipConfig,
    httpConfig,
    leavingDelay,
    stateAfterJoining,
    healthCheckConfig
  )

}
