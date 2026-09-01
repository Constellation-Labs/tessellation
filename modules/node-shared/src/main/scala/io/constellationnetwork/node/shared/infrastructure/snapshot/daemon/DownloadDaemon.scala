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
import io.constellationnetwork.node.shared.domain.node.{DownloadMode, NodeStorage}
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
                  attemptDownloadWithRetry
                )(_ => downloadLock.release)
              case false =>
                logger.debug("Download already in progress, skipping duplicate trigger")
            }
          }
          .compile
          .drain

    /** Attempts download, retrying with exponential backoff on failure.
      *
      * The nodeStates stream only emits on state *transitions*, so if download fails while the node is already in WaitingForDownload, no
      * new event is published and the stream never re-fires. This method loops internally until download succeeds or the node leaves
      * WaitingForDownload.
      */
    private def attemptDownloadWithRetry: F[Unit] = {
      val maxBackoff = 60.seconds

      def go(attempt: Int, backoff: FiniteDuration): F[Unit] =
        nodeStorage.getNodeState.flatMap {
          case NodeState.WaitingForDownload =>
            nodeStorage.getDownloadMode
              .flatTap(mode => logger.info(s"[DownloadDaemon] Download attempt $attempt, mode=$mode"))
              .flatMap { mode =>
                val downloadAction = mode match {
                  case DownloadMode.Full =>
                    logger.info("[DownloadDaemon] Using full download path") >>
                      download.download(hasherSelector)
                  case DownloadMode.Recovery =>
                    logger.info("[DownloadDaemon] Using incremental recovery download path") >>
                      download.recoveryDownload(hasherSelector)
                  case DownloadMode.FollowerCatchUp =>
                    logger.info("[DownloadDaemon] Using bounded follower catch-up path") >>
                      download.followerCatchUp(hasherSelector)
                }
                (peerDiscoveryDelay.waitForPeers >> downloadAction)
                  .flatTap(_ => nodeStorage.clearRecoveryDownload)
                  .handleErrorWith { err =>
                    val nextBackoff = (backoff * 2).min(maxBackoff)
                    // If the full download failed because genesis is unavailable (peers don't
                    // serve it), switch to incremental recovery on the next attempt. This handles
                    // validators with persisted snapshots but no snapshot_info anchor — the full
                    // download falls back to genesis which is too old for any peer to serve.
                    // The recovery path doesn't need snapshot_info; it downloads from the tip.
                    //
                    // Detection is via the `RecoveryFallbackEligible` marker trait mixed into the
                    // concrete error case objects in each layer's Download.scala. Using a marker
                    // trait rather than `getClass.getSimpleName.contains(...)` means a rename of
                    // those errors fails at compile time instead of silently breaking the switch.
                    val shouldSwitchToRecovery = mode == DownloadMode.Full && err.isInstanceOf[RecoveryFallbackEligible]
                    val switchAction =
                      (logger.warn(
                        s"[DownloadDaemon] Full download failed with recovery-eligible error (${err.getClass.getSimpleName}); switching to recovery path"
                      ) >> nodeStorage.setRecoveryDownload).whenA(shouldSwitchToRecovery)
                    logger.error(err)(
                      s"[DownloadDaemon] Download attempt $attempt failed, retrying in ${backoff.toSeconds}s"
                    ) >> switchAction >> Async[F].sleep(backoff) >> go(attempt + 1, nextBackoff)
                  }
              }
          case other =>
            logger.info(s"[DownloadDaemon] Node no longer in WaitingForDownload (state=$other), aborting retry loop") >>
              nodeStorage.clearRecoveryDownload
        }

      go(attempt = 1, backoff = 10.seconds)
    }
  }
}
