package org.tessellation.modules

import cats.effect.kernel.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.config.types.SnapshotConfig
import org.tessellation.domain.cluster.storage.AddressStorage
import org.tessellation.domain.snapshot.GlobalSnapshotStorage
import org.tessellation.domain.trust.storage.TrustStorage
import org.tessellation.infrastructure.cluster.storage.AddressStorage
import org.tessellation.infrastructure.db.Database
import org.tessellation.infrastructure.snapshot.{GlobalSnapshotLocalFileSystemStorage, GlobalSnapshotStorage, genesis}
import org.tessellation.infrastructure.trust.storage.TrustStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.tessellation.sdk.domain.gossip.RumorStorage
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.modules.SdkStorages

object Storages {

  def make[F[_]: Async: Database: KryoSerializer](
    sdkStorages: SdkStorages[F],
    snapshotConfig: SnapshotConfig
  ): F[Storages[F]] =
    for {
      addressStorage <- AddressStorage.make[F]
      trustStorage <- TrustStorage.make[F]
      globalSnapshotLocalFileSystemStorage <- GlobalSnapshotLocalFileSystemStorage.make(
        snapshotConfig.globalSnapshotPath
      )
      globalSnapshotStorage <- GlobalSnapshotStorage
        .make[F](globalSnapshotLocalFileSystemStorage, genesis, snapshotConfig.inMemoryCapacity)
    } yield
      new Storages[F](
        address = addressStorage,
        cluster = sdkStorages.cluster,
        node = sdkStorages.node,
        session = sdkStorages.session,
        rumor = sdkStorages.rumor,
        trust = trustStorage,
        globalSnapshot = globalSnapshotStorage
      ) {}
}

sealed abstract class Storages[F[_]] private (
  val address: AddressStorage[F],
  val cluster: ClusterStorage[F],
  val node: NodeStorage[F],
  val session: SessionStorage[F],
  val rumor: RumorStorage[F],
  val trust: TrustStorage[F],
  val globalSnapshot: GlobalSnapshotStorage[F]
)
