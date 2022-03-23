package org.tessellation.modules

import cats.effect.Async

import org.tessellation.domain.cluster.programs.TrustPush
import org.tessellation.domain.snapshot.programs.Download
import org.tessellation.http.p2p.P2PClient
import org.tessellation.sdk.domain.cluster.programs.{Joining, PeerDiscovery}
import org.tessellation.sdk.modules.SdkPrograms

object Programs {

  def make[F[_]: Async](
    sdkPrograms: SdkPrograms[F],
    storages: Storages[F],
    services: Services[F],
    p2pClient: P2PClient[F]
  ): Programs[F] = {
    val trustPush = TrustPush.make(storages.trust, services.gossip)
    val download = Download
      .make(
        storages.node,
        storages.cluster,
        p2pClient.globalSnapshot,
        storages.globalSnapshot,
        services.consensus.storage
      )

    new Programs[F](sdkPrograms.peerDiscovery, sdkPrograms.joining, trustPush, download) {}
  }
}

sealed abstract class Programs[F[_]] private (
  val peerDiscovery: PeerDiscovery[F],
  val joining: Joining[F],
  val trustPush: TrustPush[F],
  val download: Download[F]
)
