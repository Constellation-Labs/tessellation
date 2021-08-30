package org.tesselation.config

import scala.concurrent.duration.FiniteDuration

import com.comcast.ip4s.{Host, Port}

object types {

  case class AppConfig(
    environment: AppEnvironment,
    httpClientConfig: HttpClientConfig,
    publicHttp: HttpServerConfig,
    p2pHttp: HttpServerConfig,
    ownerHttp: HttpServerConfig,
    healthcheckHttp: HttpServerConfig
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
