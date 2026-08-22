package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import io.constellationnetwork.schema.SnapshotOrdinal

import weaver.FunSuite

object SnapshotInfoCertifiedRetentionSuite extends FunSuite {

  private def ordinal(value: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(value)

  test("certified replay retains every stored context from its public root while preserving logarithmic anchors") {
    val stored = (0L to 10L).map(ordinal).toSet
    val logarithmic = Set(ordinal(0L), ordinal(4L), ordinal(8L), ordinal(10L))
    val retained = SnapshotStorage.retainedSnapshotInfoOrdinals(
      stored,
      logarithmic,
      current = ordinal(10L),
      certifiedReplayRoot = Some(ordinal(6L))
    )

    expect.same(logarithmic ++ (6L to 10L).map(ordinal), retained)
  }

  test("disabled certified replay leaves the existing logarithmic retention policy unchanged") {
    val stored = (0L to 10L).map(ordinal).toSet
    val logarithmic = Set(ordinal(0L), ordinal(4L), ordinal(8L), ordinal(10L))

    expect.same(
      logarithmic,
      SnapshotStorage.retainedSnapshotInfoOrdinals(stored, logarithmic, ordinal(10L), None)
    )
  }
}
