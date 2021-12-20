package org.tessellation.config

import scala.concurrent.duration.FiniteDuration

import org.tessellation.sdk.config.types.GossipConfig

import ciris.Secret
import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.types.string.NonEmptyString

object types {

  case class AppConfig(
    environment: AppEnvironment,
    httpConfig: HttpConfig,
    dbConfig: DBConfig,
    gossipConfig: GossipConfig,
    trustConfig: TrustConfig
  )

  case class DBConfig(
    driver: NonEmptyString,
    url: NonEmptyString,
    user: NonEmptyString,
    password: Secret[String]
  )

  case class HttpClientConfig(
    timeout: FiniteDuration,
    idleTimeInPool: FiniteDuration
  )

  case class TrustDaemonConfig(
    interval: FiniteDuration
  )

  case class TrustConfig(
    daemon: TrustDaemonConfig
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
