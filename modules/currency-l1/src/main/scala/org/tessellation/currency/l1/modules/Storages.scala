package org.tessellation.currency.l1.modules

import cats.effect.kernel.Async
import cats.effect.std.Random
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.consensus.block.storage.ConsensusStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.dag.l1.infrastructure.address.storage.AddressStorage
import org.tessellation.dag.l1.modules.{Storages => BaseStorages}
import org.tessellation.node.shared.domain.cluster.storage.L0ClusterStorage
import org.tessellation.node.shared.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.node.shared.infrastructure.cluster.storage.L0ClusterStorage
import org.tessellation.node.shared.infrastructure.snapshot.storage.LastSnapshotStorage
import org.tessellation.node.shared.modules.SharedStorages
import org.tessellation.schema.address.Address
import org.tessellation.schema.peer.L0Peer
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import org.tessellation.schema.transaction.TransactionReference
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import org.tessellation.security.Hasher

object Storages {

  def make[
    F[_]: Async: Random: Hasher,
    P <: StateProof,
    S <: Snapshot,
    SI <: SnapshotInfo[P]
  ](
    sharedStorages: SharedStorages[F],
    l0Peer: L0Peer,
    globalL0Peer: L0Peer,
    currencyIdentifier: Address
  ): F[Storages[F, P, S, SI]] =
    for {
      blockStorage <- BlockStorage.make[F]
      consensusStorage <- ConsensusStorage.make[F]
      l0ClusterStorage <- L0ClusterStorage.make[F](l0Peer)
      globalL0ClusterStorage <- L0ClusterStorage.make[F](globalL0Peer)
      lastCurrencySnapshotStorage <- LastSnapshotStorage.make[F, S, SI]
      lastGlobalSnapshotStorage <- LastSnapshotStorage.make[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]
      transactionStorage <- TransactionReference.emptyCurrency(currencyIdentifier).flatMap {
        TransactionStorage.make[F](_)
      }
      addressStorage <- AddressStorage.make[F]
    } yield
      new Storages[F, P, S, SI] {
        val address = addressStorage
        val block = blockStorage
        val consensus = consensusStorage
        val cluster = sharedStorages.cluster
        val l0Cluster = l0ClusterStorage
        val globalL0Cluster = globalL0ClusterStorage
        val lastSnapshot = lastCurrencySnapshotStorage
        val lastGlobalSnapshot = lastGlobalSnapshotStorage
        val node = sharedStorages.node
        val session = sharedStorages.session
        val rumor = sharedStorages.rumor
        val transaction = transactionStorage
      }
}

sealed abstract class Storages[
  F[_],
  P <: StateProof,
  S <: Snapshot,
  SI <: SnapshotInfo[P]
] extends BaseStorages[F, P, S, SI] {

  val globalL0Cluster: L0ClusterStorage[F]
  val lastGlobalSnapshot: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]
}
