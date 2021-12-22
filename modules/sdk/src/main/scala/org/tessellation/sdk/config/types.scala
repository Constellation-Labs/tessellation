package org.tessellation.sdk.config

import scala.concurrent.duration.FiniteDuration

import com.comcast.ip4s.{Host, Port}

object types {

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
}
