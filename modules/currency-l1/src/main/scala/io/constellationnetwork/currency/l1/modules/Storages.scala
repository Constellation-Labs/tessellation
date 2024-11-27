package io.constellationnetwork.currency.l1.modules

import cats.effect.kernel.Async
import cats.effect.std.Random
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.dag.l1.domain.block.BlockStorage
import io.constellationnetwork.dag.l1.domain.consensus.block.storage.ConsensusStorage
import io.constellationnetwork.dag.l1.domain.transaction.{ContextualTransactionValidator, TransactionStorage}
import io.constellationnetwork.dag.l1.infrastructure.address.storage.AddressStorage
import io.constellationnetwork.dag.l1.modules.{Storages => BaseStorages}
import io.constellationnetwork.node.shared.domain.cluster.storage.L0ClusterStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.domain.swap.{AllowSpendStorage, ContextualAllowSpendValidator}
import io.constellationnetwork.node.shared.domain.tokenlock.{ContextualTokenLockValidator, TokenLockStorage}
import io.constellationnetwork.node.shared.infrastructure.cluster.storage.L0ClusterStorage
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.modules.SharedStorages
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.peer.L0Peer
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import io.constellationnetwork.schema.swap.AllowSpendReference
import io.constellationnetwork.schema.tokenLock.TokenLockReference
import io.constellationnetwork.schema.transaction.TransactionReference
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import io.constellationnetwork.security.Hasher

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
    currencyIdentifier: Address,
    contextualTransactionValidator: ContextualTransactionValidator,
    contextualAllowSpendValidator: ContextualAllowSpendValidator,
    contextualTokenLockValidator: ContextualTokenLockValidator
  ): F[Storages[F, P, S, SI]] =
    for {
      blockStorage <- BlockStorage.make[F]
      consensusStorage <- ConsensusStorage.make[F]
      l0ClusterStorage <- L0ClusterStorage.make[F](l0Peer)
      globalL0ClusterStorage <- L0ClusterStorage.make[F](globalL0Peer)
      lastCurrencySnapshotStorage <- LastSnapshotStorage.make[F, S, SI]
      lastGlobalSnapshotStorage <- LastSnapshotStorage.make[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]
      transactionStorage <- TransactionReference.emptyCurrency(currencyIdentifier).flatMap {
        TransactionStorage.make[F](_, contextualTransactionValidator)
      }
      allowSpendStorage <- AllowSpendReference.emptyCurrency(currencyIdentifier).flatMap {
        AllowSpendStorage.make[F](_, contextualAllowSpendValidator)
      }
      tokenLockStorage <- TokenLockReference.emptyCurrency(currencyIdentifier).flatMap {
        TokenLockStorage.make[F](_, contextualTokenLockValidator)
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
        val allowSpend = allowSpendStorage
        val tokenLock = tokenLockStorage
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
