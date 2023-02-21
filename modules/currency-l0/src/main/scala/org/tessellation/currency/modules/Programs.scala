package org.tessellation.currency.modules

import cats.effect.Async

import org.tessellation.currency.domain.snapshot.programs.Download
import org.tessellation.sdk.domain.cluster.programs.{Joining, PeerDiscovery}
import org.tessellation.sdk.modules.SdkPrograms

object Programs {

  def make[F[_]: Async](
    sdkPrograms: SdkPrograms[F],
    storages: Storages[F],
    services: Services[F]
  ): Programs[F] = {
    val download = Download
      .make(
        storages.node,
        services.consensus
      )

    new Programs[F](sdkPrograms.peerDiscovery, sdkPrograms.joining, download) {}
  }
}

sealed abstract class Programs[F[_]] private (
  val peerDiscovery: PeerDiscovery[F],
  val joining: Joining[F],
  val download: Download[F]
)
