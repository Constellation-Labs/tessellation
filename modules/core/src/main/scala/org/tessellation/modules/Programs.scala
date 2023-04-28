package org.tessellation.modules

import java.security.KeyPair

import cats.effect.Async
import cats.effect.std.Random

import org.tessellation.config.types.AppConfig
import org.tessellation.domain.cluster.programs.TrustPush
import org.tessellation.infrastructure.snapshot.programs.RollbackLoader
import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.domain.cluster.programs.{Joining, PeerDiscovery}
import org.tessellation.sdk.domain.snapshot.PeerSelect
import org.tessellation.sdk.http.p2p.clients.L0GlobalSnapshotClient
import org.tessellation.sdk.infrastructure.snapshot.MajorityPeerSelect
import org.tessellation.sdk.infrastructure.snapshot.programs.Download
import org.tessellation.sdk.modules.SdkPrograms
import org.tessellation.security.SecurityProvider

object Programs {

  def make[F[_]: Async: KryoSerializer: SecurityProvider: Random](
    sdkPrograms: SdkPrograms[F],
    storages: Storages[F],
    services: Services[F],
    keyPair: KeyPair,
    config: AppConfig,
    l0GlobalSnapshotClient: L0GlobalSnapshotClient[F]
  ): Programs[F] = {
    val trustPush = TrustPush.make(storages.trust, services.gossip)
    val peerSelect: PeerSelect[F] = MajorityPeerSelect.make(storages.cluster, l0GlobalSnapshotClient)
    val download = Download
      .make(
        storages.node,
        services.consensus,
        peerSelect,
        () => storages.cluster.getPeers
      )
    val rollbackLoader = RollbackLoader.make(
      keyPair,
      config.snapshot,
      storages.incrementalGlobalSnapshotLocalFileSystemStorage,
      services.snapshotContextFunctions
    )

    new Programs[F](sdkPrograms.peerDiscovery, sdkPrograms.joining, trustPush, download, rollbackLoader) {}
  }
}

sealed abstract class Programs[F[_]] private (
  val peerDiscovery: PeerDiscovery[F],
  val joining: Joining[F],
  val trustPush: TrustPush[F],
  val download: Download[F],
  val rollbackLoader: RollbackLoader[F]
)
