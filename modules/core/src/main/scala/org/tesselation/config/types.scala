package org.tesselation.config

import scala.concurrent.duration.FiniteDuration

import com.comcast.ip4s.{Host, Port}

object types {

  case class AppConfig(
    httpClientConfig: HttpClientConfig,
    httpServerConfig: HttpServerConfig
  )

  case class HttpServerConfig(
    host: Host,
    port: Port
  )

  case class HttpClientConfig(
    timeout: FiniteDuration,
    idleTimeInPool: FiniteDuration
  )

}
