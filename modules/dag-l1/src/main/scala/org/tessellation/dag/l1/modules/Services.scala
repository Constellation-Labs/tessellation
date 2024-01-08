package org.tessellation.dag.l1.modules

import cats.data.NonEmptySet
import cats.effect.kernel.Async

import org.tessellation.dag.l1.config.types.AppConfig
import org.tessellation.dag.l1.domain.block.BlockService
import org.tessellation.dag.l1.domain.transaction.TransactionService
import org.tessellation.dag.l1.http.p2p.P2PClient
import org.tessellation.node.shared.domain.cluster.services.{Cluster, Session}
import org.tessellation.node.shared.domain.cluster.storage.L0ClusterStorage
import org.tessellation.node.shared.domain.collateral.Collateral
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.domain.healthcheck.LocalHealthcheck
import org.tessellation.node.shared.domain.snapshot.services.GlobalL0Service
import org.tessellation.node.shared.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.node.shared.infrastructure.block.processing.BlockAcceptanceManager
import org.tessellation.node.shared.infrastructure.collateral.Collateral
import org.tessellation.node.shared.modules.SharedServices
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import org.tessellation.security.{Hasher, SecurityProvider}

object Services {

  def make[
    F[_]: Async: SecurityProvider: Hasher,
    P <: StateProof,
    S <: Snapshot,
    SI <: SnapshotInfo[P]
  ](
    storages: Storages[F, P, S, SI],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    globalL0Cluster: L0ClusterStorage[F],
    validators: Validators[F],
    sharedServices: SharedServices[F],
    p2PClient: P2PClient[F],
    cfg: AppConfig,
    maybeMajorityPeerIds: Option[NonEmptySet[PeerId]]
  ): Services[F, P, S, SI] =
    new Services[F, P, S, SI] {
      val localHealthcheck = sharedServices.localHealthcheck
      val block = BlockService.make[F](
        BlockAcceptanceManager.make[F](validators.block),
        storages.address,
        storages.block,
        storages.transaction,
        cfg.collateral.amount
      )
      val cluster = sharedServices.cluster
      val gossip = sharedServices.gossip
      val globalL0 = GlobalL0Service
        .make[F](p2PClient.l0GlobalSnapshot, globalL0Cluster, lastGlobalSnapshotStorage, None, maybeMajorityPeerIds)
      val session = sharedServices.session
      val transaction = TransactionService.make[F](storages.transaction, validators.transactionContextual)
      val collateral = Collateral.make[F](cfg.collateral, storages.lastSnapshot)
    }
}

trait Services[F[_], P <: StateProof, S <: Snapshot, SI <: SnapshotInfo[P]] {
  val localHealthcheck: LocalHealthcheck[F]
  val block: BlockService[F]
  val cluster: Cluster[F]
  val gossip: Gossip[F]
  val globalL0: GlobalL0Service[F]
  val session: Session[F]
  val transaction: TransactionService[F]
  val collateral: Collateral[F]
}
