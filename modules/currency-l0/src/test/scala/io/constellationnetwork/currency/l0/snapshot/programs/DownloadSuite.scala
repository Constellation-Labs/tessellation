package io.constellationnetwork.currency.l0.snapshot.programs

import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.node.NodeState

import weaver.SimpleIOSuite

object DownloadSuite extends SimpleIOSuite {

  test("downloads retain the release/mainnet four-snapshot observation window") {
    val current = SnapshotOrdinal.unsafeApply(10L)

    expect
      .same(
        SnapshotOrdinal.unsafeApply(14L),
        Download.observationLimit(current, 4L)
      )
      .pure[IO]
  }

  test("successor retries are bounded and only active download states re-anchor") {
    expect
      .all(
        Download.fetchNextRetryCap === 6,
        Download.shouldReanchorAfterFailure(NodeState.DownloadInProgress),
        Download.shouldReanchorAfterFailure(NodeState.WaitingForObserving),
        Download.shouldReanchorAfterFailure(NodeState.Observing),
        Download.shouldReanchorAfterFailure(NodeState.WaitingForReady),
        !Download.shouldReanchorAfterFailure(NodeState.WaitingForDownload),
        !Download.shouldReanchorAfterFailure(NodeState.Ready),
        !Download.shouldReanchorAfterFailure(NodeState.Leaving)
      )
      .pure[IO]
  }
}
