package org.tessellation.currency.l1.modules

import cats.Order
import cats.effect.kernel.Async
import cats.effect.std.Random
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.consensus.block.storage.ConsensusStorage
import org.tessellation.dag.l1.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.dag.l1.infrastructure.address.storage.AddressStorage
import org.tessellation.dag.l1.modules.{Storages => BaseStorages}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.L0Peer
import org.tessellation.schema.snapshot.Snapshot
import org.tessellation.schema.transaction.Transaction
import org.tessellation.schema.{Block, GlobalSnapshot}
import org.tessellation.sdk.domain.cluster.storage.L0ClusterStorage
import org.tessellation.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.sdk.infrastructure.cluster.storage.L0ClusterStorage
import org.tessellation.sdk.modules.SdkStorages

object Storages {

  def make[
    F[_]: Async: Random: KryoSerializer,
    T <: Transaction: Order: Ordering,
    B <: Block[T],
    S <: Snapshot[T, B]
  ](
    sdkStorages: SdkStorages[F],
    l0Peer: L0Peer,
    globalL0Peer: L0Peer
  ): F[Storages[F, T, B, S]] =
    for {
      blockStorage <- BlockStorage.make[F, B]
      consensusStorage <- ConsensusStorage.make[F, T, B]
      l0ClusterStorage <- L0ClusterStorage.make[F](l0Peer)
      globalL0ClusterStorage <- L0ClusterStorage.make[F](globalL0Peer)
      lastCurrencySnapshotStorage <- LastSnapshotStorage.make[F, S]
      lastGlobalSnapshotStorage <- LastSnapshotStorage.make[F, GlobalSnapshot]
      transactionStorage <- TransactionStorage.make[F, T]
      addressStorage <- AddressStorage.make[F]
    } yield
      new Storages[F, T, B, S] {
        val address = addressStorage
        val block = blockStorage
        val consensus = consensusStorage
        val cluster = sdkStorages.cluster
        val l0Cluster = l0ClusterStorage
        val globalL0Cluster = globalL0ClusterStorage
        val lastSnapshot = lastCurrencySnapshotStorage
        val lastGlobalSnapshot = lastGlobalSnapshotStorage
        val node = sdkStorages.node
        val session = sdkStorages.session
        val rumor = sdkStorages.rumor
        val transaction = transactionStorage
      }
}

sealed abstract class Storages[F[_], T <: Transaction: Order: Ordering, B <: Block[T], S <: Snapshot[T, B]]
    extends BaseStorages[F, T, B, S] {

  val globalL0Cluster: L0ClusterStorage[F]
  val lastGlobalSnapshot: LastSnapshotStorage[F, GlobalSnapshot]
}
