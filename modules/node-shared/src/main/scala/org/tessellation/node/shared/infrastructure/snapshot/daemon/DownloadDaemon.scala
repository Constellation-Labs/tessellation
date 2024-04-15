package org.tessellation.node.shared.infrastructure.snapshot.daemon

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.node.shared.domain.Daemon
import org.tessellation.node.shared.domain.node.NodeStorage
import org.tessellation.node.shared.domain.snapshot.PeerDiscoveryDelay
import org.tessellation.node.shared.domain.snapshot.programs.Download
import org.tessellation.schema.node.NodeState
import org.tessellation.security.HasherSelector

trait DownloadDaemon[F[_]] extends Daemon[F] {}

object DownloadDaemon {

  def make[F[_]: Async](
    nodeStorage: NodeStorage[F],
    download: Download[F],
    peerDiscoveryDelay: PeerDiscoveryDelay[F],
    hasherSelector: HasherSelector[F]
  )(
    implicit S: Supervisor[F]
  ): DownloadDaemon[F] = new DownloadDaemon[F] {

    def start: F[Unit] = S.supervise(watchForDownload()).void

    private def watchForDownload(): F[Unit] =
      nodeStorage.nodeStates
        .filter(_ === NodeState.WaitingForDownload)
        .evalTap { _ =>
          peerDiscoveryDelay.waitForPeers >> download.download(hasherSelector)
        }
        .compile
        .drain
  }
}
