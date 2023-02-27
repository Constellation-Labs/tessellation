package org.tessellation.dag.l1.modules

import cats.Eq
import cats.effect.kernel.Async

import org.tessellation.dag.l1.config.types.AppConfig
import org.tessellation.dag.l1.domain.block.BlockService
import org.tessellation.dag.l1.domain.transaction.TransactionService
import org.tessellation.dag.l1.http.p2p.P2PClient
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.snapshot.Snapshot
import org.tessellation.schema.transaction.Transaction
import org.tessellation.schema.{Block, GlobalSnapshot}
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

  def make[F[_]: Async: SecurityProvider: KryoSerializer, T <: Transaction: Eq, B <: Block[T]: Eq: Ordering, S <: Snapshot[T, B]](
    storages: Storages[F, T, B, S],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalSnapshot],
    globalL0Cluster: L0ClusterStorage[F],
    validators: Validators[F, T, B],
    sdkServices: SdkServices[F],
    p2PClient: P2PClient[F, T, B],
    cfg: AppConfig
  ): Services[F, T, B] =
    new Services[F, T, B](
      localHealthcheck = sdkServices.localHealthcheck,
      block = BlockService.make[F, T, B](
        BlockAcceptanceManager.make[F, T, B](validators.block),
        storages.address,
        storages.block,
        storages.transaction,
        cfg.collateral.amount
      ),
      cluster = sdkServices.cluster,
      gossip = sdkServices.gossip,
      globalL0 = GlobalL0Service
        .make[F](p2PClient.l0GlobalSnapshotClient, globalL0Cluster, lastGlobalSnapshotStorage, None),
      session = sdkServices.session,
      transaction = TransactionService.make[F, T](storages.transaction, validators.transactionContextual),
      collateral = Collateral.make[F](cfg.collateral, storages.lastSnapshot)
    ) {}
}

sealed abstract class Services[F[_], T <: Transaction, B <: Block[T]] private (
  val localHealthcheck: LocalHealthcheck[F],
  val block: BlockService[F, T, B],
  val cluster: Cluster[F],
  val gossip: Gossip[F],
  val globalL0: GlobalL0Service[F],
  val session: Session[F],
  val transaction: TransactionService[F, T],
  val collateral: Collateral[F]
)
