package org.tesselation.config

import scala.concurrent.duration.FiniteDuration

import com.comcast.ip4s.Port

object types {

  case class AppConfig(
    environment: AppEnvironment,
    httpClientConfig: HttpClientConfig,
    publicHttpPort: Port,
    p2pHttpPort: Port,
    healthcheckHttpPort: Port
  )

  case class HttpClientConfig(
    timeout: FiniteDuration,
    idleTimeInPool: FiniteDuration
  )

}
