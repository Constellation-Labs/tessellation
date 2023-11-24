package org.tessellation.sdk.infrastructure.snapshot.daemon

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.schema.node.NodeState
import org.tessellation.sdk.domain.Daemon
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.domain.snapshot.PeerDiscoveryDelay
import org.tessellation.sdk.domain.snapshot.programs.Download

trait DownloadDaemon[F[_]] extends Daemon[F] {}

object DownloadDaemon {

  def make[F[_]: Async](
    nodeStorage: NodeStorage[F],
    download: Download[F],
    peerDiscoveryDelay: PeerDiscoveryDelay[F]
  )(
    implicit S: Supervisor[F]
  ): DownloadDaemon[F] = new DownloadDaemon[F] {

    def start: F[Unit] = S.supervise(watchForDownload).void

    private def watchForDownload: F[Unit] =
      nodeStorage.nodeStates
        .filter(_ === NodeState.WaitingForDownload)
        .evalTap { _ =>
          peerDiscoveryDelay.waitForPeers >> download.download
        }
        .compile
        .drain
  }
}
