package org.tessellation.dag.l1.modules

import cats.Order
import cats.effect.kernel.Async
import cats.effect.std.Random
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.consensus.block.storage.ConsensusStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.dag.l1.infrastructure.address.storage.AddressStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.Block
import org.tessellation.schema.peer.L0Peer
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import org.tessellation.schema.transaction.Transaction
import org.tessellation.sdk.domain.cluster.storage.{ClusterStorage, L0ClusterStorage, SessionStorage}
import org.tessellation.sdk.domain.collateral.LatestBalances
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.sdk.infrastructure.cluster.storage.L0ClusterStorage
import org.tessellation.sdk.infrastructure.gossip.RumorStorage
import org.tessellation.sdk.infrastructure.snapshot.storage.LastSnapshotStorage
import org.tessellation.sdk.modules.SdkStorages

object Storages {

  def make[F[_]: Async: Random: KryoSerializer, T <: Transaction: Order: Ordering, B <: Block[T], P <: StateProof, S <: Snapshot[
    T,
    B
  ], SI <: SnapshotInfo[P]](
    sdkStorages: SdkStorages[F],
    l0Peer: L0Peer
  ): F[Storages[F, T, B, P, S, SI]] =
    for {
      blockStorage <- BlockStorage.make[F, B]
      consensusStorage <- ConsensusStorage.make[F, T, B]
      l0ClusterStorage <- L0ClusterStorage.make[F](l0Peer)
      lastSnapshotStorage <- LastSnapshotStorage.make[F, S, SI]
      transactionStorage <- TransactionStorage.make[F, T]
      addressStorage <- AddressStorage.make[F]
    } yield
      new Storages[F, T, B, P, S, SI] {
        val address = addressStorage
        val block = blockStorage
        val consensus = consensusStorage
        val cluster = sdkStorages.cluster
        val l0Cluster = l0ClusterStorage
        val lastSnapshot = lastSnapshotStorage
        val node = sdkStorages.node
        val session = sdkStorages.session
        val rumor = sdkStorages.rumor
        val transaction = transactionStorage
      }
}

trait Storages[F[_], T <: Transaction, B <: Block[T], P <: StateProof, S <: Snapshot[T, B], SI <: SnapshotInfo[P]] {
  val address: AddressStorage[F]
  val block: BlockStorage[F, B]
  val consensus: ConsensusStorage[F, T, B]
  val cluster: ClusterStorage[F]
  val l0Cluster: L0ClusterStorage[F]
  val lastSnapshot: LastSnapshotStorage[F, S, SI] with LatestBalances[F]
  val node: NodeStorage[F]
  val session: SessionStorage[F]
  val rumor: RumorStorage[F]
  val transaction: TransactionStorage[F, T]
}
