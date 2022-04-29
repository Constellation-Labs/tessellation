package org.tessellation.dag.l1.modules

import cats.effect.kernel.Async

import org.tessellation.dag.l1.domain.block.BlockService
import org.tessellation.dag.l1.domain.snapshot.services.L0Service
import org.tessellation.dag.l1.domain.transaction.TransactionService
import org.tessellation.dag.l1.http.p2p.P2PClient
import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.domain.cluster.services.{Cluster, Session}
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.modules.SdkServices
import org.tessellation.security.SecurityProvider

object Services {

  def make[F[_]: Async: SecurityProvider: KryoSerializer](
    storages: Storages[F],
    validators: Validators[F],
    sdkServices: SdkServices[F],
    p2PClient: P2PClient[F]
  ): Services[F] =
    new Services[F](
      block = BlockService.make[F](storages.address, storages.block, validators.block, storages.transaction),
      cluster = sdkServices.cluster,
      gossip = sdkServices.gossip,
      l0 = L0Service
        .make[F](p2PClient.l0GlobalSnapshotClient, storages.l0Cluster, storages.lastGlobalSnapshotStorage),
      session = sdkServices.session,
      transaction = TransactionService.make[F](storages.transaction, validators.transaction)
    ) {}
}

sealed abstract class Services[F[_]] private (
  val block: BlockService[F],
  val cluster: Cluster[F],
  val gossip: Gossip[F],
  val l0: L0Service[F],
  val session: Session[F],
  val transaction: TransactionService[F]
)
