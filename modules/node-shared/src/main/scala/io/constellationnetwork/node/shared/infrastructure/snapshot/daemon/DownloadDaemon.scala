package io.constellationnetwork.node.shared.infrastructure.snapshot.daemon

import cats.effect.Async
import cats.effect.std.{Semaphore, Supervisor}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.domain.Daemon
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.PeerDiscoveryDelay
import io.constellationnetwork.node.shared.domain.snapshot.programs.Download
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.snapshot.Snapshot
import io.constellationnetwork.security.HasherSelector

import org.typelevel.log4cats.slf4j.Slf4jLogger

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

    private val logger = Slf4jLogger.getLoggerFromClass[F](DownloadDaemon.getClass)

    def start: F[Unit] =
      logger.info("[DownloadDaemon] Starting download daemon") >>
        Semaphore[F](1).flatMap { downloadLock =>
          logger.info("[DownloadDaemon] Created semaphore, supervising watchForDownload stream") >>
            S.supervise(watchForDownload(downloadLock)).void
        }

    private def watchForDownload(downloadLock: Semaphore[F]): F[Unit] =
      logger.info("[DownloadDaemon] Stream subscription started, waiting for WaitingForDownload state...") >>
        nodeStorage.nodeStates
          .evalTap(state => logger.debug(s"[DownloadDaemon] State event received: $state"))
          .filter(_ === NodeState.WaitingForDownload)
          .evalTap { _ =>
            downloadLock.tryAcquire.flatMap {
              case true =>
                Async[F].guaranteeCase(
                  nodeStorage.isRecoveryDownload
                    .flatTap(flag => logger.info(s"[DownloadDaemon] WaitingForDownload triggered, isRecovery=$flag"))
                    .flatMap { isRecovery =>
                      val downloadAction = if (isRecovery) {
                        logger.info("[DownloadDaemon] Using incremental recovery download path") >>
                          download.recoveryDownload(hasherSelector)
                      } else {
                        logger.info("[DownloadDaemon] Using full download path") >>
                          download.download(hasherSelector)
                      }
                      (peerDiscoveryDelay.waitForPeers >> downloadAction)
                        .flatTap(_ => nodeStorage.clearRecoveryDownload)
                    }
                    .handleErrorWith { err =>
                      logger.error(err)(
                        "Download failed, stream kept alive. " +
                          "Node remains in WaitingForDownload — will retry after 10s backoff."
                      ) >> Async[F].sleep(10.seconds)
                    // Do NOT clear recoveryDownload flag here — preserve it so retries
                    // still use the incremental recovery path instead of full download.
                    }
                )(_ => downloadLock.release)
              case false =>
                logger.debug("Download already in progress, skipping duplicate trigger")
            }
          }
          .compile
          .drain
  }
}
