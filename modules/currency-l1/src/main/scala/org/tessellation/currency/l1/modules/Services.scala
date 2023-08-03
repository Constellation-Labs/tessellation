package org.tessellation.currency.l1.modules

import cats.effect.kernel.Async
import cats.effect.std.Random

import org.tessellation.currency.dataApplication.BaseDataApplicationL1Service
import org.tessellation.currency.l1.http.p2p.P2PClient
import org.tessellation.currency.schema.currency._
import org.tessellation.dag.l1.config.types.AppConfig
import org.tessellation.dag.l1.domain.block.BlockService
import org.tessellation.dag.l1.domain.transaction.TransactionService
import org.tessellation.dag.l1.modules.{Services => BaseServices, Validators}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import org.tessellation.schema.{Block, GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import org.tessellation.sdk.domain.cluster.storage.L0ClusterStorage
import org.tessellation.sdk.domain.snapshot.services.GlobalL0Service
import org.tessellation.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.sdk.infrastructure.Collateral
import org.tessellation.sdk.infrastructure.block.processing.BlockAcceptanceManager
import org.tessellation.sdk.modules.SdkServices
import org.tessellation.security.SecurityProvider

object Services {
  def make[F[_]: Async: Random: KryoSerializer: SecurityProvider](
    storages: Storages[
      F,
      CurrencyBlock,
      CurrencySnapshotStateProof,
      CurrencyIncrementalSnapshot,
      CurrencySnapshotInfo
    ],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    globalL0Cluster: L0ClusterStorage[F],
    validators: Validators[F, CurrencyBlock],
    sdkServices: SdkServices[F],
    p2PClient: P2PClient[F, CurrencyBlock],
    cfg: AppConfig,
    maybeDataApplication: Option[BaseDataApplicationL1Service[F]]
  ): Services[F, CurrencyBlock, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo] =
    new Services[F, CurrencyBlock, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo] {

      val localHealthcheck = sdkServices.localHealthcheck
      val block = BlockService.make[F, CurrencyBlock](
        BlockAcceptanceManager.make[F, CurrencyBlock](validators.block),
        storages.address,
        storages.block,
        storages.transaction,
        cfg.collateral.amount
      )
      val cluster = sdkServices.cluster
      val gossip = sdkServices.gossip
      val globalL0 =
        GlobalL0Service.make[F](p2PClient.l0GlobalSnapshot, globalL0Cluster, lastGlobalSnapshotStorage, None)
      val session = sdkServices.session
      val transaction = TransactionService.make[F](storages.transaction, validators.transactionContextual)
      val collateral = Collateral.make[F](cfg.collateral, storages.lastSnapshot)
      val dataApplication = maybeDataApplication
    }
}

sealed abstract class Services[F[_], B <: Block, P <: StateProof, S <: Snapshot[B], SI <: SnapshotInfo[P]]
    extends BaseServices[F, B, P, S, SI] {
  val dataApplication: Option[BaseDataApplicationL1Service[F]]
}
