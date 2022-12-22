package org.tessellation.dag.l1.modules

import cats.effect.Async
import cats.effect.std.Random

import org.tessellation.dag.l1.domain.snapshot.programs.SnapshotProcessor
import org.tessellation.dag.l1.http.p2p.P2PClient
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.security.SecurityProvider
import org.tessellation.sdk.domain.cluster.programs.{Joining, L0PeerDiscovery, PeerDiscovery}
import org.tessellation.sdk.modules.SdkPrograms

object Programs {

  def make[F[_]: Async: KryoSerializer: SecurityProvider: Random](
    sdkPrograms: SdkPrograms[F],
    p2pClient: P2PClient[F],
    storages: Storages[F]
  ): Programs[F] = {
    val l0PeerDiscovery = L0PeerDiscovery.make(p2pClient.l0Cluster, storages.l0Cluster)
    val snapshotProcessor = SnapshotProcessor
      .make(storages.address, storages.block, storages.lastGlobalSnapshotStorage, storages.transaction)

    new Programs[F](sdkPrograms.peerDiscovery, l0PeerDiscovery, sdkPrograms.joining, snapshotProcessor) {}
  }
}

sealed abstract class Programs[F[_]] private (
  val peerDiscovery: PeerDiscovery[F],
  val l0PeerDiscovery: L0PeerDiscovery[F],
  val joining: Joining[F],
  val snapshotProcessor: SnapshotProcessor[F]
)
