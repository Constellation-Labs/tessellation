package org.tessellation.dag.l1.config

import org.tessellation.dag.l1.domain.consensus.block.config.ConsensusConfig
import org.tessellation.node.shared.config.types._

object types {

  case class AppConfigReader(
    consensus: ConsensusConfig
  )

  case class AppConfig(
    consensus: ConsensusConfig,
    shared: SharedConfig
  ) {
    val environment = shared.environment
    val http = shared.http
    val gossip = shared.gossip
    val collateral = shared.collateral
    val priorityPeerIds = shared.priorityPeerIds
  }
}
