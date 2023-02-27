package org.tessellation.dag.l1.domain.consensus.block

import java.security.KeyPair

import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.consensus.block.config.ConsensusConfig
import org.tessellation.dag.l1.domain.consensus.block.http.p2p.clients.BlockConsensusClient
import org.tessellation.dag.l1.domain.consensus.block.storage.ConsensusStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.schema.Block
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.Transaction
import org.tessellation.sdk.domain.block.processing.BlockValidator
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.transaction.TransactionValidator

case class BlockConsensusContext[F[_], T <: Transaction, B <: Block[T]](
  blockConsensusClient: BlockConsensusClient[F, T],
  blockStorage: BlockStorage[F, B],
  blockValidator: BlockValidator[F, T, B],
  clusterStorage: ClusterStorage[F],
  consensusConfig: ConsensusConfig,
  consensusStorage: ConsensusStorage[F, T, B],
  keyPair: KeyPair,
  selfId: PeerId,
  transactionStorage: TransactionStorage[F, T],
  transactionValidator: TransactionValidator[F, T]
)
