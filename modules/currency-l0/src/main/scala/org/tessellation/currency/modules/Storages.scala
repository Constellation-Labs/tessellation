package org.tessellation.currency.modules

import cats.effect.kernel.Async
import cats.effect.std.Supervisor
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.currency.config.types.SnapshotConfig
import org.tessellation.currency.infrastructure.snapshot.{CurrencySnapshotLocalFileSystemStorage, CurrencySnapshotStorage}
import org.tessellation.currency.schema.currency.CurrencySnapshot
import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.tessellation.sdk.domain.collateral.LatestBalances
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.sdk.infrastructure.gossip.RumorStorage
import org.tessellation.sdk.modules.SdkStorages

object Storages {

  def make[F[_]: Async: KryoSerializer: Supervisor](
    sdkStorages: SdkStorages[F],
    snapshotConfig: SnapshotConfig
  ): F[Storages[F]] =
    for {
      snapshotLocalFileSystemStorage <- CurrencySnapshotLocalFileSystemStorage.make(
        snapshotConfig.snapshotPath
      )
      snapshotStorage <- CurrencySnapshotStorage
        .make[F](snapshotLocalFileSystemStorage, snapshotConfig.inMemoryCapacity)
    } yield
      new Storages[F](
        cluster = sdkStorages.cluster,
        node = sdkStorages.node,
        session = sdkStorages.session,
        rumor = sdkStorages.rumor,
        snapshot = snapshotStorage
      ) {}
}

sealed abstract class Storages[F[_]] private (
  val cluster: ClusterStorage[F],
  val node: NodeStorage[F],
  val session: SessionStorage[F],
  val rumor: RumorStorage[F],
  val snapshot: SnapshotStorage[F, CurrencySnapshot] with LatestBalances[F]
)
