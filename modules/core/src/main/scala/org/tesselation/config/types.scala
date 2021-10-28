package org.tesselation.config

import scala.concurrent.duration.FiniteDuration

import ciris.Secret
import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.types.string.NonEmptyString

object types {

  case class AppConfig(
    environment: AppEnvironment,
    keyConfig: KeyConfig,
    httpConfig: HttpConfig,
    dbConfig: DBConfig,
    gossipConfig: GossipConfig
  )

  case class DBConfig(
    driver: NonEmptyString,
    url: NonEmptyString,
    user: NonEmptyString,
    password: Secret[String]
  )

  case class KeyConfig(
    keystore: String,
    storepass: Secret[String],
    keypass: Secret[String],
    keyalias: Secret[String]
  )

  case class HttpClientConfig(
    timeout: FiniteDuration,
    idleTimeInPool: FiniteDuration
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
