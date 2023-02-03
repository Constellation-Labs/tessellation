package org.tessellation.modules

import cats.effect.kernel.Async
import cats.effect.std.Supervisor
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.config.types.SnapshotConfig
import org.tessellation.domain.snapshot.GlobalSnapshotStorage
import org.tessellation.domain.trust.storage.TrustStorage
import org.tessellation.infrastructure.snapshot.{GlobalSnapshotLocalFileSystemStorage, GlobalSnapshotStorage}
import org.tessellation.infrastructure.trust.storage.TrustStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.tessellation.sdk.domain.collateral.LatestBalances
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.infrastructure.gossip.RumorStorage
import org.tessellation.sdk.modules.SdkStorages
import org.tessellation.security.hash.Hash

object Storages {

  def make[F[_]: Async: KryoSerializer: Supervisor](
    sdkStorages: SdkStorages[F],
    snapshotConfig: SnapshotConfig,
    maybeRollbackHash: Option[Hash]
  ): F[Storages[F]] =
    for {
      trustStorage <- TrustStorage.make[F]
      globalSnapshotLocalFileSystemStorage <- GlobalSnapshotLocalFileSystemStorage.make(
        snapshotConfig.globalSnapshotPath
      )
      globalSnapshotStorage <- GlobalSnapshotStorage
        .make[F](globalSnapshotLocalFileSystemStorage, snapshotConfig.inMemoryCapacity, maybeRollbackHash)
    } yield
      new Storages[F](
        cluster = sdkStorages.cluster,
        node = sdkStorages.node,
        session = sdkStorages.session,
        rumor = sdkStorages.rumor,
        trust = trustStorage,
        globalSnapshot = globalSnapshotStorage
      ) {}
}

sealed abstract class Storages[F[_]] private (
  val cluster: ClusterStorage[F],
  val node: NodeStorage[F],
  val session: SessionStorage[F],
  val rumor: RumorStorage[F],
  val trust: TrustStorage[F],
  val globalSnapshot: GlobalSnapshotStorage[F] with LatestBalances[F]
)
