package org.tessellation.dag.l1.modules

import cats.Eq
import cats.effect.kernel.Async

import org.tessellation.dag.l1.config.types.AppConfig
import org.tessellation.dag.l1.domain.block.BlockService
import org.tessellation.dag.l1.domain.transaction.TransactionService
import org.tessellation.dag.l1.http.p2p.P2PClient
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import org.tessellation.schema.{Block, GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import org.tessellation.sdk.domain.cluster.services.{Cluster, Session}
import org.tessellation.sdk.domain.cluster.storage.L0ClusterStorage
import org.tessellation.sdk.domain.collateral.Collateral
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.healthcheck.LocalHealthcheck
import org.tessellation.sdk.domain.snapshot.services.GlobalL0Service
import org.tessellation.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.sdk.infrastructure.Collateral
import org.tessellation.sdk.infrastructure.block.processing.BlockAcceptanceManager
import org.tessellation.sdk.modules.SdkServices
import org.tessellation.security.SecurityProvider

object Services {

  def make[
    F[_]: Async: SecurityProvider: KryoSerializer,
    B <: Block: Eq: Ordering,
    P <: StateProof,
    S <: Snapshot[B],
    SI <: SnapshotInfo[P]
  ](
    storages: Storages[F, B, P, S, SI],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    globalL0Cluster: L0ClusterStorage[F],
    validators: Validators[F, B],
    sdkServices: SdkServices[F],
    p2PClient: P2PClient[F, B],
    cfg: AppConfig
  ): Services[F, B, P, S, SI] =
    new Services[F, B, P, S, SI] {
      val localHealthcheck = sdkServices.localHealthcheck
      val block = BlockService.make[F, B](
        BlockAcceptanceManager.make[F, B](validators.block),
        storages.address,
        storages.block,
        storages.transaction,
        cfg.collateral.amount
      )
      val cluster = sdkServices.cluster
      val gossip = sdkServices.gossip
      val globalL0 = GlobalL0Service
        .make[F](p2PClient.l0GlobalSnapshot, globalL0Cluster, lastGlobalSnapshotStorage, None)
      val session = sdkServices.session
      val transaction = TransactionService.make[F](storages.transaction, validators.transactionContextual)
      val collateral = Collateral.make[F](cfg.collateral, storages.lastSnapshot)
    }
}

trait Services[F[_], B <: Block, P <: StateProof, S <: Snapshot[B], SI <: SnapshotInfo[P]] {
  val localHealthcheck: LocalHealthcheck[F]
  val block: BlockService[F, B]
  val cluster: Cluster[F]
  val gossip: Gossip[F]
  val globalL0: GlobalL0Service[F]
  val session: Session[F]
  val transaction: TransactionService[F]
  val collateral: Collateral[F]
}
