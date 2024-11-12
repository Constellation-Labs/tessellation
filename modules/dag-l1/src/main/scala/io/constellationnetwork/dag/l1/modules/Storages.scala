package io.constellationnetwork.dag.l1.modules

import cats.effect.kernel.Async
import cats.effect.std.Random
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.dag.l1.domain.address.storage.AddressStorage
import io.constellationnetwork.dag.l1.domain.block.BlockStorage
import io.constellationnetwork.dag.l1.domain.consensus.block.storage.ConsensusStorage
import io.constellationnetwork.dag.l1.domain.transaction.{ContextualTransactionValidator, TransactionStorage}
import io.constellationnetwork.dag.l1.infrastructure.address.storage.AddressStorage
import io.constellationnetwork.node.shared.domain.cluster.storage.{ClusterStorage, L0ClusterStorage, SessionStorage}
import io.constellationnetwork.node.shared.domain.collateral.LatestBalances
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.domain.swap.{AllowSpendStorage, ContextualAllowSpendValidator}
import io.constellationnetwork.node.shared.infrastructure.cluster.storage.L0ClusterStorage
import io.constellationnetwork.node.shared.infrastructure.gossip.RumorStorage
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.modules.SharedStorages
import io.constellationnetwork.schema.peer.L0Peer
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import io.constellationnetwork.schema.swap.AllowSpendReference
import io.constellationnetwork.schema.transaction.TransactionReference

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
      transactionStorage <- TransactionStorage.make[F](TransactionReference.empty, contextualTransactionValidator)
      addressStorage <- AddressStorage.make[F]
      allowSpendStorage <- AllowSpendStorage.make[F](AllowSpendReference.empty, contextualAllowSpendValidator)
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
        val allowSpend = allowSpendStorage
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
  val allowSpend: AllowSpendStorage[F]
}
