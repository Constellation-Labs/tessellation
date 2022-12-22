package org.tessellation.dag.l1.modules

import cats.effect.kernel.Async

import org.tessellation.dag.block.processing.BlockAcceptanceManager
import org.tessellation.dag.l1.config.types.AppConfig
import org.tessellation.dag.l1.domain.block.BlockService
import org.tessellation.dag.l1.domain.transaction.TransactionService
import org.tessellation.dag.l1.http.p2p.P2PClient
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.security.SecurityProvider
import org.tessellation.sdk.domain.cluster.services.{Cluster, Session}
import org.tessellation.sdk.domain.collateral.Collateral
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.healthcheck.LocalHealthcheck
import org.tessellation.sdk.domain.snapshot.services.L0Service
import org.tessellation.sdk.infrastructure.Collateral
import org.tessellation.sdk.modules.SdkServices

object Services {

  def make[F[_]: Async: SecurityProvider: KryoSerializer](
    storages: Storages[F],
    validators: Validators[F],
    sdkServices: SdkServices[F],
    p2PClient: P2PClient[F],
    cfg: AppConfig
  ): Services[F] =
    new Services[F](
      localHealthcheck = sdkServices.localHealthcheck,
      block = BlockService.make[F](
        BlockAcceptanceManager.make[F](validators.block),
        storages.address,
        storages.block,
        storages.transaction,
        cfg.collateral.amount
      ),
      cluster = sdkServices.cluster,
      gossip = sdkServices.gossip,
      l0 = L0Service
        .make[F](p2PClient.l0GlobalSnapshotClient, storages.l0Cluster, storages.lastGlobalSnapshotStorage, None),
      session = sdkServices.session,
      transaction = TransactionService.make[F](storages.transaction, validators.transactionContextual),
      collateral = Collateral.make[F](cfg.collateral, storages.lastGlobalSnapshotStorage)
    ) {}
}

sealed abstract class Services[F[_]] private (
  val localHealthcheck: LocalHealthcheck[F],
  val block: BlockService[F],
  val cluster: Cluster[F],
  val gossip: Gossip[F],
  val l0: L0Service[F],
  val session: Session[F],
  val transaction: TransactionService[F],
  val collateral: Collateral[F]
)
