package org.tessellation.sdk.config

import scala.concurrent.duration.FiniteDuration

import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.types.numeric.PosInt

object types {

  case class SdkConfig(
    environment: AppEnvironment,
    gossipConfig: GossipConfig,
    httpConfig: HttpConfig,
    leavingDelay: FiniteDuration,
    healthCheck: HealthCheckConfig
  )

  case class RumorStorageConfig(
    activeRetention: FiniteDuration,
    seenRetention: FiniteDuration
  )

  case class GossipDaemonConfig(
    fanout: Int,
    interval: FiniteDuration,
    maxConcurrentHandlers: Int
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
    removeUnresponsiveParallelPeersAfter: FiniteDuration
  )

  case class PingHealthCheckConfig(
    concurrentChecks: PosInt,
    defaultCheckTimeout: FiniteDuration,
    defaultCheckAttempts: PosInt,
    ensureCheckInterval: FiniteDuration
  )
}
