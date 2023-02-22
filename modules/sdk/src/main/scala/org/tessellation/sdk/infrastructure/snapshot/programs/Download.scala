package org.tessellation.sdk.infrastructure.snapshot.programs

import cats.effect.Async
import cats.syntax.flatMap._

import org.tessellation.schema.node.NodeState
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.infrastructure.snapshot.SnapshotConsensus

object Download {

  def make[F[_]: Async](
    nodeStorage: NodeStorage[F],
    consensus: SnapshotConsensus[F, _, _, _, _]
  ): Download[F] =
    new Download[F](
      nodeStorage,
      consensus
    ) {}
}

sealed abstract class Download[F[_]: Async] private (
  nodeStorage: NodeStorage[F],
  consensus: SnapshotConsensus[F, _, _, _, _]
) {

  def download(): F[Unit] =
    nodeStorage.tryModifyState(NodeState.WaitingForDownload, NodeState.WaitingForObserving) >>
      consensus.manager.startObserving
}
