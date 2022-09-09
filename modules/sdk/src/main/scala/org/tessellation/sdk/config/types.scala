package org.tessellation.sdk.config

import scala.concurrent.duration.FiniteDuration

import org.tessellation.schema.balance.Amount
import org.tessellation.schema.node.NodeState

import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.types.numeric.PosInt

object types {

  case class SdkConfig(
    environment: AppEnvironment,
    gossipConfig: GossipConfig,
    httpConfig: HttpConfig,
    leavingDelay: FiniteDuration,
    stateAfterJoining: NodeState,
    healthCheck: HealthCheckConfig
  )

  case class RumorStorageConfig(
    activeRetention: FiniteDuration,
    seenRetention: FiniteDuration
  )

  case class GossipDaemonConfig(
    fanout: Int,
    interval: FiniteDuration,
    maxConcurrentRounds: Int
  )

  case class GossipConfig(
    storage: RumorStorageConfig,
    daemon: GossipDaemonConfig
  )

  case class HttpClientConfig(
    timeout: FiniteDuration,
    idleTimeInPool: FiniteDuration
  )

  case class HttpServerConfig(
    host: Host,
    port: Port
  )

  case class HttpConfig(
    externalIp: Host,
    client: HttpClientConfig,
    publicHttp: HttpServerConfig,
    p2pHttp: HttpServerConfig,
    cliHttp: HttpServerConfig
  )

  case class HealthCheckConfig(
    ping: PingHealthCheckConfig,
    peerDeclaration: PeerDeclarationHealthCheckConfig,
    removeUnresponsiveParallelPeersAfter: FiniteDuration,
    requestProposalsAfter: FiniteDuration
  )

  case class PeerDeclarationHealthCheckConfig(
    receiveTimeout: FiniteDuration,
    triggerInterval: FiniteDuration
  )

  case class PingHealthCheckConfig(
    concurrentChecks: PosInt,
    defaultCheckTimeout: FiniteDuration,
    defaultCheckAttempts: PosInt,
    ensureCheckInterval: FiniteDuration
  )

  case class CollateralConfig(
    amount: Amount
  )
}
