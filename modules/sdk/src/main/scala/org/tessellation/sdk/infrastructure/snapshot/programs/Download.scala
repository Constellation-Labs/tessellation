package org.tessellation.sdk.infrastructure.snapshot.programs

import cats.effect.Async
import cats.syntax.flatMap._

import org.tessellation.schema.node.NodeState
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.infrastructure.snapshot.SnapshotConsensus

import org.typelevel.log4cats.slf4j.Slf4jLogger

object Download {

  def make[F[_]: Async](
    nodeStorage: NodeStorage[F],
    consensus: SnapshotConsensus[F, _, _, _, _, _]
  ): Download[F] =
    new Download[F](
      nodeStorage,
      consensus
    ) {}
}

sealed abstract class Download[F[_]: Async] private (
  nodeStorage: NodeStorage[F],
  consensus: SnapshotConsensus[F, _, _, _, _, _]
) {

  val logger = Slf4jLogger.getLoggerFromClass[F](Download.getClass)

  def download(): F[Unit] =
    nodeStorage.tryModifyState(NodeState.WaitingForDownload, NodeState.WaitingForObserving) >>
      logger.warn(s"Not implemented")
  // TODO implement the protocol
  // 1. Download all historical snapshots
  // 2. Call `consensus.manager.registerForConsensus(observationKey = lastHistoricalSnapshotOrdinal + offset)`
  // 3. Download snapshots in range from `lastHistoricalSnapshotKey` to `observationKey` (inclusive)
  //    2.1 Transition the node state from `DownloadInProgress` to `WaitingForObserving`
  //    2.2 Call `consensus.manager.startFacilitatingAfterDownload(observationKey, lastSnapshot, lastSnapshotInfo)`, where lastSnapshot is a snapshot with ordinal `observationKey`
}
