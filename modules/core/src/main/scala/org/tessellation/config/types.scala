package org.tessellation.config

import scala.concurrent.duration.FiniteDuration

import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.types.{GossipConfig, HealthCheckConfig, HttpConfig}

import ciris.Secret
import eu.timepit.refined.types.string.NonEmptyString

object types {

  case class AppConfig(
    environment: AppEnvironment,
    httpConfig: HttpConfig,
    dbConfig: DBConfig,
    gossipConfig: GossipConfig,
    trustConfig: TrustConfig,
    healthCheckConfig: HealthCheckConfig
  )

  case class DBConfig(
    driver: NonEmptyString,
    url: NonEmptyString,
    user: NonEmptyString,
    password: Secret[String]
  )

  case class TrustDaemonConfig(
    interval: FiniteDuration
  )

  case class TrustConfig(
    daemon: TrustDaemonConfig
  )

}
