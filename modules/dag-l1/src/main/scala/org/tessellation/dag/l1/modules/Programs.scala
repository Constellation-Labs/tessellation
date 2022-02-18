package org.tessellation.dag.l1.modules

import cats.effect.Sync

import org.tessellation.dag.l1.http.p2p.P2PClient
import org.tessellation.sdk.domain.cluster.programs.{Joining, L0PeerDiscovery, PeerDiscovery}
import org.tessellation.sdk.modules.SdkPrograms

object Programs {

  def make[F[_]: Sync](sdkPrograms: SdkPrograms[F], p2pClient: P2PClient[F], storages: Storages[F]): Programs[F] = {
    val l0PeerDiscovery = L0PeerDiscovery.make(p2pClient.l0Cluster, storages.l0Cluster)

    new Programs[F](sdkPrograms.peerDiscovery, l0PeerDiscovery, sdkPrograms.joining) {}
  }
}

sealed abstract class Programs[F[_]] private (
  val peerDiscovery: PeerDiscovery[F],
  val l0PeerDiscovery: L0PeerDiscovery[F],
  val joining: Joining[F]
)
