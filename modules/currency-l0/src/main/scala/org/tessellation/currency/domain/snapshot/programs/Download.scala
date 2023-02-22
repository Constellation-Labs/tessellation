package org.tessellation.currency.domain.snapshot.programs

import cats.effect.Async
import cats.syntax.flatMap._

import org.tessellation.currency.schema.currency.{CurrencyBlock, CurrencySnapshot, CurrencyTransaction}
import org.tessellation.schema.node.NodeState
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.infrastructure.snapshot.SnapshotConsensus

object Download {

  def make[F[_]: Async](
    nodeStorage: NodeStorage[F],
    consensus: SnapshotConsensus[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshot]
  ): Download[F] =
    new Download[F](
      nodeStorage,
      consensus
    ) {}
}

sealed abstract class Download[F[_]: Async] private (
  nodeStorage: NodeStorage[F],
  consensus: SnapshotConsensus[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshot]
) {

  def download(): F[Unit] =
    nodeStorage.tryModifyState(NodeState.WaitingForDownload, NodeState.WaitingForObserving) >>
      consensus.manager.startObserving
}
