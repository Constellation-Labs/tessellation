package io.constellationnetwork.dag.l1.config

import io.constellationnetwork.dag.l1.domain.consensus.block.config.{ConsensusConfig, DataConsensusConfig}
import io.constellationnetwork.dag.l1.domain.transaction.TransactionLimitConfig
import io.constellationnetwork.node.shared.config.types._
import io.constellationnetwork.node.shared.domain.consensus.config.SwapConsensusConfig
import io.constellationnetwork.node.shared.domain.tokenlock.consensus.config.TokenLockConsensusConfig

object types {

  case class AppConfigReader(
    consensus: ConsensusConfig,
    dataConsensus: DataConsensusConfig,
    swap: SwapConsensusConfig,
    tokenLock: TokenLockConsensusConfig,
    transactionLimit: TransactionLimitConfig
  )

  case class AppConfig(
    consensus: ConsensusConfig,
    dataConsensus: DataConsensusConfig,
    swap: SwapConsensusConfig,
    tokenLock: TokenLockConsensusConfig,
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
