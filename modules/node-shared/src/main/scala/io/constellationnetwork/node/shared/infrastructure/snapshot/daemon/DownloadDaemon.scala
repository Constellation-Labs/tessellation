package io.constellationnetwork.node.shared.infrastructure.snapshot.daemon

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.node.shared.domain.Daemon
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.PeerDiscoveryDelay
import io.constellationnetwork.node.shared.domain.snapshot.programs.Download
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.snapshot.Snapshot
import io.constellationnetwork.security.HasherSelector

trait DownloadDaemon[F[_]] extends Daemon[F] {}

object DownloadDaemon {

  def make[F[_]: Async, S <: Snapshot](
    nodeStorage: NodeStorage[F],
    download: Download[F, S],
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
