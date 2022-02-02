package org.tessellation.dag.l1.modules

import cats.effect.kernel.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.dag.l1.config.TipsConfig
import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.consensus.block.storage.ConsensusStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.dag.l1.infrastructure.address.storage.AddressStorage
import org.tessellation.dag.l1.infrastructure.db.Database
import org.tessellation.sdk.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.tessellation.sdk.domain.gossip.RumorStorage
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.modules.SdkStorages

object Storages {

  def make[F[_]: Async: Database](
    tipsConfig: TipsConfig,
    sdkStorages: SdkStorages[F]
  ): F[Storages[F]] =
    for {
      blockStorage <- BlockStorage.make[F](tipsConfig)
      consensusStorage <- ConsensusStorage.make[F]
      transactionStorage <- TransactionStorage.make[F]
      addressStorage = AddressStorage.make[F]
    } yield
      new Storages[F](
        address = addressStorage,
        block = blockStorage,
        consensus = consensusStorage,
        cluster = sdkStorages.cluster,
        node = sdkStorages.node,
        session = sdkStorages.session,
        rumor = sdkStorages.rumor,
        transaction = transactionStorage
      ) {}
}

sealed abstract class Storages[F[_]] private (
  val address: AddressStorage[F],
  val block: BlockStorage[F],
  val consensus: ConsensusStorage[F],
  val cluster: ClusterStorage[F],
  val node: NodeStorage[F],
  val session: SessionStorage[F],
  val rumor: RumorStorage[F],
  val transaction: TransactionStorage[F]
)
