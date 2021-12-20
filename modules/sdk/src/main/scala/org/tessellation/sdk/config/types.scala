package org.tessellation.sdk.config

import scala.concurrent.duration.FiniteDuration

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

}
