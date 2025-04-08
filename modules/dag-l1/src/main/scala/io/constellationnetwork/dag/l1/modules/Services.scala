package io.constellationnetwork.dag.l1.modules

import cats.Parallel
import cats.data.NonEmptySet
import cats.effect.kernel.Async

import io.constellationnetwork.dag.l1.config.types.AppConfig
import io.constellationnetwork.dag.l1.domain.block.BlockService
import io.constellationnetwork.dag.l1.domain.swap.block.AllowSpendBlockService
import io.constellationnetwork.dag.l1.domain.tokenlock.block.TokenLockBlockService
import io.constellationnetwork.dag.l1.domain.transaction.TransactionService
import io.constellationnetwork.dag.l1.http.p2p.P2PClient
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.domain.cluster.services.{Cluster, Session}
import io.constellationnetwork.node.shared.domain.cluster.storage.L0ClusterStorage
import io.constellationnetwork.node.shared.domain.collateral.Collateral
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.healthcheck.LocalHealthcheck
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.domain.swap.AllowSpendService
import io.constellationnetwork.node.shared.domain.swap.block.AllowSpendBlockAcceptanceManager
import io.constellationnetwork.node.shared.domain.tokenlock.TokenLockService
import io.constellationnetwork.node.shared.domain.tokenlock.block.TokenLockBlockAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.block.processing.BlockAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.collateral.Collateral
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.modules.SharedServices
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import io.constellationnetwork.security.{Hasher, HasherSelector, SecurityProvider}

object Services {

  def make[
    F[_]: Async: Parallel: SecurityProvider: HasherSelector,
    P <: StateProof,
    S <: Snapshot,
    SI <: SnapshotInfo[P],
    R <: CliMethod
  ](
    storages: Storages[F, P, S, SI],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    globalL0Cluster: L0ClusterStorage[F],
    validators: Validators[F],
    sharedServices: SharedServices[F, R],
    p2PClient: P2PClient[F],
    cfg: AppConfig,
    maybeMajorityPeerIds: Option[NonEmptySet[PeerId]],
    txHasher: Hasher[F]
  ): Services[F, P, S, SI, R] =
    new Services[F, P, S, SI, R] {
      val localHealthcheck = sharedServices.localHealthcheck
      val block = BlockService.make[F](
        BlockAcceptanceManager.make[F](validators.block, txHasher),
        storages.address,
        storages.block,
        storages.transaction,
        lastGlobalSnapshotStorage,
        cfg.collateral.amount,
        txHasher
      )
      val cluster = sharedServices.cluster
      val gossip = sharedServices.gossip
      val globalL0 = GlobalL0Service
        .make[F](p2PClient.l0GlobalSnapshot, globalL0Cluster, lastGlobalSnapshotStorage, None, maybeMajorityPeerIds)
      val session = sharedServices.session
      val transaction = TransactionService.make[F, P, S, SI](storages.transaction, storages.lastSnapshot, validators.transaction)
      val allowSpend =
        AllowSpendService.make[F, P, S, SI](storages.allowSpend, storages.lastSnapshot, validators.allowSpend)
      val allowSpendBlock = AllowSpendBlockService.make[F](
        AllowSpendBlockAcceptanceManager.make[F](validators.allowSpendBlock),
        storages.address,
        storages.allowSpendBlock,
        storages.allowSpend,
        cfg.collateral.amount
      )
      val tokenLock =
        TokenLockService.make[F, P, S, SI](storages.tokenLock, storages.lastSnapshot, validators.tokenLock)
      val tokenLockBlock = TokenLockBlockService.make[F](
        TokenLockBlockAcceptanceManager.make[F](validators.tokenLockBlock),
        storages.address,
        storages.tokenLockBlock,
        storages.tokenLock,
        cfg.collateral.amount
      )
      val collateral = Collateral.make[F](cfg.collateral, storages.lastSnapshot)
      val restart = sharedServices.restart
    }
}

trait Services[F[_], P <: StateProof, S <: Snapshot, SI <: SnapshotInfo[P], R <: CliMethod] {
  val localHealthcheck: LocalHealthcheck[F]
  val block: BlockService[F]
  val cluster: Cluster[F]
  val gossip: Gossip[F]
  val globalL0: GlobalL0Service[F]
  val session: Session[F]
  val transaction: TransactionService[F]
  val allowSpend: AllowSpendService[F]
  val allowSpendBlock: AllowSpendBlockService[F]
  val tokenLock: TokenLockService[F]
  val tokenLockBlock: TokenLockBlockService[F]
  val collateral: Collateral[F]
  val restart: RestartService[F, R]
}
