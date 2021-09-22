package org.tesselation.config

import scala.concurrent.duration.FiniteDuration

import ciris.Secret
import com.comcast.ip4s.{Host, Port}

object types {

  case class AppConfig(
    environment: AppEnvironment,
    keyConfig: KeyConfig,
    httpClientConfig: HttpClientConfig,
    externalIp: Host,
    publicHttp: HttpServerConfig,
    p2pHttp: HttpServerConfig,
    cliHttp: HttpServerConfig
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

  case class HttpServerConfig(
    host: Host,
    port: Port
  )

}
