package org.tessellation.sdk.cli

import scala.concurrent.duration._

import org.tessellation.cli.env._
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.types._

trait CliMethod {

  val keyStore: StorePath
  val alias: KeyAlias
  val password: Password

  val environment: AppEnvironment

  val httpConfig: HttpConfig

  val gossipConfig: GossipConfig = GossipConfig(
    storage = RumorStorageConfig(
      activeRetention = 2.seconds,
      seenRetention = 2.minutes
    ),
    daemon = GossipDaemonConfig(
      fanout = 2,
      interval = 0.2.seconds,
      maxConcurrentHandlers = 20
    )
  )

  val sdkConfig: SdkConfig = SdkConfig(
    environment,
    gossipConfig,
    httpConfig
  )

}
