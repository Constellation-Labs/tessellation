package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import cats.effect.IO

import io.constellationnetwork.schema.SnapshotOrdinal

import weaver.SimpleIOSuite

object LastSyncGlobalSnapshotStorageSuite extends SimpleIOSuite {

  private def ordinal(value: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(value)

  test("retained exact contexts are bounded and evict the least recently used ordinal") {
    val initial = Vector(1L, 2L, 3L, 4L).foldLeft(Vector.empty[(SnapshotOrdinal, String)]) {
      case (cache, value) => LastSyncGlobalSnapshotStorage.retainExact(cache, ordinal(value), s"context-$value", capacity = 4)
    }
    val (touched, found) = LastSyncGlobalSnapshotStorage.takeRetainedExact(initial, ordinal(1L))
    val updated = LastSyncGlobalSnapshotStorage.retainExact(touched, ordinal(5L), "context-5", capacity = 4)

    IO.pure(
      expect.same(Some("context-1"), found) &&
        expect.same(Vector(3L, 4L, 1L, 5L), updated.map(_._1.value.value))
    )
  }

  test("retaining the same exact ordinal replaces its context without growing the cache") {
    val initial = Vector(ordinal(7L) -> "old", ordinal(8L) -> "eight")
    val updated = LastSyncGlobalSnapshotStorage.retainExact(initial, ordinal(7L), "new", capacity = 4)

    IO.pure(
      expect.same(Vector(8L, 7L), updated.map(_._1.value.value)) &&
        expect.same(Vector("eight", "new"), updated.map(_._2))
    )
  }

  test("a missing exact ordinal does not change access order") {
    val initial = Vector(ordinal(2L) -> "two", ordinal(3L) -> "three")
    val (updated, found) = LastSyncGlobalSnapshotStorage.takeRetainedExact(initial, ordinal(1L))

    IO.pure(expect.same(initial, updated) && expect(found.isEmpty))
  }
}
