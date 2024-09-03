package io.constellationnetwork.dag.l1.config

import io.constellationnetwork.dag.l1.domain.consensus.block.config.{ConsensusConfig, DataConsensusConfig}
import io.constellationnetwork.dag.l1.domain.transaction.TransactionLimitConfig
import io.constellationnetwork.node.shared.config.types._

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
