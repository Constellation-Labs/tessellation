package org.tessellation.dag.l1.domain.consensus.block

import java.security.KeyPair

import org.tessellation.dag.block.BlockValidator
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.consensus.block.config.ConsensusConfig
import org.tessellation.dag.l1.domain.consensus.block.http.p2p.clients.BlockConsensusClient
import org.tessellation.dag.l1.domain.consensus.block.storage.ConsensusStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.dag.transaction.TransactionValidator
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage

case class BlockConsensusContext[F[_]](
  blockConsensusClient: BlockConsensusClient[F],
  blockStorage: BlockStorage[F],
  blockValidator: BlockValidator[F],
  clusterStorage: ClusterStorage[F],
  consensusConfig: ConsensusConfig,
  consensusStorage: ConsensusStorage[F],
  keyPair: KeyPair,
  selfId: PeerId,
  transactionStorage: TransactionStorage[F],
  transactionValidator: TransactionValidator[F]
)
