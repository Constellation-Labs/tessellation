package org.tessellation.domain.snapshot.programs

import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._

import org.tessellation.domain.snapshot.GlobalSnapshotStorage
import org.tessellation.http.p2p.clients.GlobalSnapshotClient
import org.tessellation.infrastructure.snapshot._
import org.tessellation.schema.node.NodeState
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.node.NodeStorage

import org.typelevel.log4cats.slf4j.Slf4jLogger

object Download {

  def make[F[_]: Async](
    nodeStorage: NodeStorage[F],
    clusterStorage: ClusterStorage[F],
    globalSnapshotClient: GlobalSnapshotClient[F],
    globalSnapshotStorage: GlobalSnapshotStorage[F],
    consensusStorage: GlobalSnapshotConsensusStorage[F]
  ): Download[F] =
    new Download(nodeStorage, clusterStorage, globalSnapshotClient, globalSnapshotStorage, consensusStorage) {}
}

sealed abstract class Download[F[_]: Async] private (
  nodeStorage: NodeStorage[F],
  clusterStorage: ClusterStorage[F],
  globalSnapshotClient: GlobalSnapshotClient[F],
  globalSnapshotStorage: GlobalSnapshotStorage[F],
  consensusStorage: GlobalSnapshotConsensusStorage[F]
) {
  private def logger = Slf4jLogger.getLogger[F]

  def download(): F[Unit] =
    nodeStorage.tryModifyState(NodeState.WaitingForDownload, NodeState.DownloadInProgress, NodeState.Ready) {
      clusterStorage.getPeers
        .map(_.headOption)
        .flatMap {
          case None =>
            (new Throwable(s"Unexpected state during download. No peer found, but node should be already connected."))
              .raiseError[F, Unit]
          case Some(peer) =>
            globalSnapshotClient.getLatest
              .run(peer)
              .flatMap { snapshot =>
                globalSnapshotStorage.prepend(snapshot) >>
                  consensusStorage.setLastKeyAndArtifact((snapshot.value.ordinal, snapshot.value).some)
              }
        }
        .handleErrorWith { err =>
          logger.error(err)(s"Error during download process") >>
            err.raiseError[F, Unit]
        }
    }
}
