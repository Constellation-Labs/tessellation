package io.constellationnetwork.dag.l1.domain.consensus.block

import java.security.KeyPair

import io.constellationnetwork.dag.l1.domain.block.BlockStorage
import io.constellationnetwork.dag.l1.domain.consensus.block.config.ConsensusConfig
import io.constellationnetwork.dag.l1.domain.consensus.block.http.p2p.clients.BlockConsensusClient
import io.constellationnetwork.dag.l1.domain.consensus.block.storage.ConsensusStorage
import io.constellationnetwork.dag.l1.domain.transaction.TransactionStorage
import io.constellationnetwork.node.shared.domain.block.processing.BlockValidator
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.transaction.TransactionValidator
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.Hasher

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
  transactionValidator: TransactionValidator[F],
  txHasher: Hasher[F]
)
