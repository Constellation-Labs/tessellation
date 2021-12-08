package org.tessellation.dag.l1

import java.security.KeyPair

import cats.effect.std.Queue

import org.tessellation.dag.l1.storage.{BlockStorage, ConsensusStorage, TransactionStorage}
import org.tessellation.domain.cluster.storage.ClusterStorage
import org.tessellation.domain.gossip.Gossip
import org.tessellation.domain.node.NodeStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.SecurityProvider

import org.http4s.client.Client

case class DAGStateChannelContext[F[_]](
  myId: PeerId,
  keyPair: KeyPair,
  blockStorage: BlockStorage[F],
  blockValidator: DAGBlockValidator[F], // TODO: figure out to not have dependencies, just storages here?
  client: Client[F],
  clusterStorage: ClusterStorage[F],
  gossip: Gossip[F],
  consensusConfig: ConsensusConfig,
  consensusStorage: ConsensusStorage[F],
  nodeStorage: NodeStorage[F],
  transactionStorage: TransactionStorage[F],
  l1PeerDAGBlockDataQueue: Queue[F, L1PeerDAGBlockData],
  l1PeerDAGBlockQueue: Queue[F, FinalBlock],
  kryoSerializer: KryoSerializer[F],
  securityProvider: SecurityProvider[F]
)
