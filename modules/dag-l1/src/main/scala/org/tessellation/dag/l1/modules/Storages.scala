package org.tessellation.dag.l1.modules

import cats.effect.kernel.Async
import cats.effect.std.Random
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.consensus.block.storage.ConsensusStorage
import org.tessellation.dag.l1.domain.transaction.{ContextualAllowSpendValidator, ContextualTransactionValidator, TransactionStorage}
import org.tessellation.dag.l1.infrastructure.address.storage.AddressStorage
import org.tessellation.node.shared.domain.cluster.storage.{ClusterStorage, L0ClusterStorage, SessionStorage}
import org.tessellation.node.shared.domain.collateral.LatestBalances
import org.tessellation.node.shared.domain.node.NodeStorage
import org.tessellation.node.shared.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.node.shared.infrastructure.cluster.storage.L0ClusterStorage
import org.tessellation.node.shared.infrastructure.gossip.RumorStorage
import org.tessellation.node.shared.infrastructure.snapshot.storage.LastSnapshotStorage
import org.tessellation.node.shared.modules.SharedStorages
import org.tessellation.schema.peer.L0Peer
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import org.tessellation.schema.swap.AllowSpendReference
import org.tessellation.schema.transaction.TransactionReference

object Storages {

  def make[F[_]: Async: Random, P <: StateProof, S <: Snapshot, SI <: SnapshotInfo[P]](
    sharedStorages: SharedStorages[F],
    l0Peer: L0Peer,
    contextualTransactionValidator: ContextualTransactionValidator,
    contextualAllowSpendValidator: ContextualAllowSpendValidator
  ): F[Storages[F, P, S, SI]] =
    for {
      blockStorage <- BlockStorage.make[F]
      consensusStorage <- ConsensusStorage.make[F]
      l0ClusterStorage <- L0ClusterStorage.make[F](l0Peer)
      lastSnapshotStorage <- LastSnapshotStorage.make[F, S, SI]
      transactionStorage <- TransactionStorage
        .make[F](TransactionReference.empty, AllowSpendReference.empty, contextualTransactionValidator, contextualAllowSpendValidator)
      addressStorage <- AddressStorage.make[F]
    } yield
      new Storages[F, P, S, SI] {
        val address = addressStorage
        val block = blockStorage
        val consensus = consensusStorage
        val cluster = sharedStorages.cluster
        val l0Cluster = l0ClusterStorage
        val lastSnapshot = lastSnapshotStorage
        val node = sharedStorages.node
        val session = sharedStorages.session
        val rumor = sharedStorages.rumor
        val transaction = transactionStorage
      }
}

trait Storages[F[_], P <: StateProof, S <: Snapshot, SI <: SnapshotInfo[P]] {
  val address: AddressStorage[F]
  val block: BlockStorage[F]
  val consensus: ConsensusStorage[F]
  val cluster: ClusterStorage[F]
  val l0Cluster: L0ClusterStorage[F]
  val lastSnapshot: LastSnapshotStorage[F, S, SI] with LatestBalances[F]
  val node: NodeStorage[F]
  val session: SessionStorage[F]
  val rumor: RumorStorage[F]
  val transaction: TransactionStorage[F]
}
