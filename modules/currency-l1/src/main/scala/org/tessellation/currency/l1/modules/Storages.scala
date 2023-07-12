package org.tessellation.currency.l1.modules

import cats.Order
import cats.effect.kernel.Async
import cats.effect.std.Random
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.consensus.block.storage.ConsensusStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.dag.l1.infrastructure.address.storage.AddressStorage
import org.tessellation.dag.l1.modules.{Storages => BaseStorages}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.peer.L0Peer
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import org.tessellation.schema.transaction.{Transaction, TransactionReference}
import org.tessellation.schema.{Block, GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import org.tessellation.sdk.domain.cluster.storage.L0ClusterStorage
import org.tessellation.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.sdk.infrastructure.cluster.storage.L0ClusterStorage
import org.tessellation.sdk.infrastructure.snapshot.storage.LastSnapshotStorage
import org.tessellation.sdk.modules.SdkStorages

object Storages {

  def make[
    F[_]: Async: Random: KryoSerializer,
    T <: Transaction: Order: Ordering,
    B <: Block[T],
    P <: StateProof,
    S <: Snapshot[T, B],
    SI <: SnapshotInfo[P]
  ](
    sdkStorages: SdkStorages[F],
    l0Peer: L0Peer,
    globalL0Peer: L0Peer,
    currencyIdentifier: Address
  ): F[Storages[F, T, B, P, S, SI]] =
    for {
      blockStorage <- BlockStorage.make[F, B]
      consensusStorage <- ConsensusStorage.make[F, T, B]
      l0ClusterStorage <- L0ClusterStorage.make[F](l0Peer)
      globalL0ClusterStorage <- L0ClusterStorage.make[F](globalL0Peer)
      lastCurrencySnapshotStorage <- LastSnapshotStorage.make[F, S, SI]
      lastGlobalSnapshotStorage <- LastSnapshotStorage.make[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]
      transactionStorage <- TransactionReference.emptyCurrency(currencyIdentifier).flatMap {
        TransactionStorage.make[F, T](_)
      }
      addressStorage <- AddressStorage.make[F]
    } yield
      new Storages[F, T, B, P, S, SI] {
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

sealed abstract class Storages[
  F[_],
  T <: Transaction: Order: Ordering,
  B <: Block[T],
  P <: StateProof,
  S <: Snapshot[T, B],
  SI <: SnapshotInfo[P]
] extends BaseStorages[F, T, B, P, S, SI] {

  val globalL0Cluster: L0ClusterStorage[F]
  val lastGlobalSnapshot: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]
}
