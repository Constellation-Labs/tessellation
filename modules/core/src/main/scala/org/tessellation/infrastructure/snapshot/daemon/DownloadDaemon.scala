package org.tessellation.infrastructure.snapshot.daemon

import cats.effect.{Async, Spawn}
import cats.syntax.eq._
import cats.syntax.functor._

import org.tessellation.domain.snapshot.programs.Download
import org.tessellation.schema.node.NodeState
import org.tessellation.sdk.domain.Daemon
import org.tessellation.sdk.domain.node.NodeStorage

trait DownloadDaemon[F[_]] extends Daemon[F] {}

object DownloadDaemon {

  def make[F[_]: Async](nodeStorage: NodeStorage[F], download: Download[F]) = new DownloadDaemon[F] {
    def start: F[Unit] = Spawn[F].start(watchForDownload).void

    private def watchForDownload: F[Unit] =
      nodeStorage.nodeStates
        .filter(_ === NodeState.WaitingForDownload)
        .evalTap { _ =>
          download.download()
        }
        .compile
        .drain
  }
}
