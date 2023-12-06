package org.tessellation.dag.l0.modules

import java.security.KeyPair

import cats.effect.Async
import cats.effect.std.Random

import org.tessellation.dag.l0.config.types.AppConfig
import org.tessellation.dag.l0.domain.cluster.programs.TrustPush
import org.tessellation.dag.l0.domain.snapshot.programs.Download
import org.tessellation.dag.l0.http.p2p.P2PClient
import org.tessellation.dag.l0.infrastructure.snapshot.programs.RollbackLoader
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.cluster.programs.{Joining, PeerDiscovery}
import org.tessellation.node.shared.domain.snapshot.PeerSelect
import org.tessellation.node.shared.domain.snapshot.programs.Download
import org.tessellation.node.shared.infrastructure.snapshot.{GlobalSnapshotContextFunctions, PeerSelect}
import org.tessellation.node.shared.modules.SharedPrograms
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.security.SecurityProvider

object Programs {

  def make[F[_]: Async: KryoSerializer: SecurityProvider: Random](
    sharedPrograms: SharedPrograms[F],
    storages: Storages[F],
    services: Services[F],
    keyPair: KeyPair,
    config: AppConfig,
    lastFullGlobalSnapshotOrdinal: SnapshotOrdinal,
    p2pClient: P2PClient[F],
    globalSnapshotContextFns: GlobalSnapshotContextFunctions[F]
  ): Programs[F] = {
    val trustPush = TrustPush.make(storages.trust, services.gossip)
    val peerSelect: PeerSelect[F] = PeerSelect.make(
      storages.cluster,
      p2pClient.globalSnapshot,
      storages.trust.getBiasedTrustScores
    )
    val download: Download[F] = Download
      .make[F](
        storages.snapshotDownload,
        p2pClient,
        storages.cluster,
        lastFullGlobalSnapshotOrdinal,
        globalSnapshotContextFns: GlobalSnapshotContextFunctions[F],
        storages.node,
        services.consensus,
        peerSelect
      )
    val rollbackLoader = RollbackLoader.make(
      keyPair,
      config.snapshot,
      storages.incrementalGlobalSnapshotLocalFileSystemStorage,
      storages.globalSnapshotInfoLocalFileSystemStorage,
      storages.snapshotDownload,
      globalSnapshotContextFns
    )

    new Programs[F](sharedPrograms.peerDiscovery, sharedPrograms.joining, trustPush, download, rollbackLoader) {}
  }
}

sealed abstract class Programs[F[_]] private (
  val peerDiscovery: PeerDiscovery[F],
  val joining: Joining[F],
  val trustPush: TrustPush[F],
  val download: Download[F],
  val rollbackLoader: RollbackLoader[F]
)
