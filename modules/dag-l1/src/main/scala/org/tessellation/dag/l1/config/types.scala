package org.tessellation.dag.l1.config

import org.tessellation.dag.l1.domain.consensus.block.config.{ConsensusConfig, DataConsensusConfig}
import org.tessellation.dag.l1.domain.transaction.TransactionLimitConfig
import org.tessellation.node.shared.config.types._

object types {

  case class AppConfigReader(
    consensus: ConsensusConfig,
    dataConsensus: DataConsensusConfig,
    transactionLimit: TransactionLimitConfig
  )

  case class AppConfig(
    consensus: ConsensusConfig,
    dataConsensus: DataConsensusConfig,
    transactionLimit: TransactionLimitConfig,
    shared: SharedConfig
  ) {
    val environment = shared.environment
    val http = shared.http
    val gossip = shared.gossip
    val collateral = shared.collateral
    val priorityPeerIds = shared.priorityPeerIds
  }
}
