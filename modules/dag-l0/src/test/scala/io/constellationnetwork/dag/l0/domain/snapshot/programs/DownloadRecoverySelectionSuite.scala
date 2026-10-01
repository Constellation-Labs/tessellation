package io.constellationnetwork.dag.l0.domain.snapshot.programs

import cats.effect.IO

import io.constellationnetwork.schema.SnapshotOrdinal

import weaver.SimpleIOSuite

object DownloadRecoverySelectionSuite extends SimpleIOSuite {

  test("persisted state with no valid recovery anchor fails instead of replaying from genesis") {
    val initial = SnapshotOrdinal.unsafeApply(123L)

    Download
      .requirePersistedRecoveryAnchor[IO, Unit](initial, None)
      .attempt
      .map {
        case Left(PersistedRecoveryAnchorsInvalid(ordinal)) => expect.same(initial, ordinal)
        case result                                         => failure(s"Expected invalid-anchor failure, got $result")
      }
  }

  test("a valid persisted recovery anchor is returned unchanged") {
    val initial = SnapshotOrdinal.unsafeApply(123L)

    Download
      .requirePersistedRecoveryAnchor[IO, String](initial, Some("anchor"))
      .map(result => expect.same("anchor", result))
  }
}
