package org.tessellation.currency.l0.modules

import cats.effect.Async
import cats.effect.std.Random

import org.tessellation.currency.l0.http.P2PClient
import org.tessellation.sdk.domain.cluster.programs.{Joining, L0PeerDiscovery, PeerDiscovery}
import org.tessellation.sdk.infrastructure.snapshot.programs.Download
import org.tessellation.sdk.modules.SdkPrograms

object Programs {

  def make[F[_]: Async: Random](
    sdkPrograms: SdkPrograms[F],
    storages: Storages[F],
    services: Services[F],
    p2pClient: P2PClient[F]
  ): Programs[F] = {
    val download = Download
      .make(
        storages.node,
        services.consensus
      )

    val globalL0PeerDiscovery = L0PeerDiscovery.make(
      p2pClient.globalL0Cluster,
      storages.globalL0Cluster
    )

    new Programs[F](sdkPrograms.peerDiscovery, globalL0PeerDiscovery, sdkPrograms.joining, download) {}
  }
}

sealed abstract class Programs[F[_]] private (
  val peerDiscovery: PeerDiscovery[F],
  val globalL0PeerDiscovery: L0PeerDiscovery[F],
  val joining: Joining[F],
  val download: Download[F]
)
