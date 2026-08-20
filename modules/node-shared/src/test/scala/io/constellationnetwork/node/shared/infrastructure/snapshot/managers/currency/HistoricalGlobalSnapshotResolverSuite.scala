package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency

import cats.effect.IO

import scala.collection.immutable.SortedSet

import io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSyncView
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency.HistoricalGlobalSnapshotResolver._
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.artifact.GlobalSnapshotsProcessed
import io.constellationnetwork.security.hash.Hash

import eu.timepit.refined.types.numeric.NonNegLong
import weaver.SimpleIOSuite

object HistoricalGlobalSnapshotResolverSuite extends SimpleIOSuite {

  private def ordinal(value: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(value)

  test("retained interval includes parent and exactly M minus one ancestors") {
    val retained = (51L to 100L).toList.map(ordinal)

    val oldest = resolve(SyncTarget, ordinal(51), ordinal(100), retainedCount = 50, retained)(identity)
    val parent = resolve(SyncTarget, ordinal(100), ordinal(100), retainedCount = 50, retained)(identity)

    IO.pure(expect.same(Right(ordinal(51)), oldest) && expect.same(Right(ordinal(100)), parent))
  }

  test("one ordinal below the retained interval is rejected before any fallback can exist") {
    val result = resolve(SyncTarget, ordinal(50), ordinal(100), retainedCount = 50, List(ordinal(50), ordinal(100)))(identity)

    IO.pure(
      result match {
        case Left(OutsideRetainedWindow(SyncTarget, required, oldest, parent)) =>
          expect.same(ordinal(50), required) && expect.same(ordinal(51), oldest) && expect.same(ordinal(100), parent)
        case other => failure(s"Expected OutsideRetainedWindow, got $other")
      }
    )
  }

  test("a gap inside the retained interval is a local synchronization fault") {
    val result = resolve(UnappliedSpendAction, ordinal(75), ordinal(100), retainedCount = 50, List(ordinal(74), ordinal(76)))(identity)

    IO.pure(
      result match {
        case Left(MissingInsideRetainedWindow(UnappliedSpendAction, required, parent)) =>
          expect.same(ordinal(75), required) && expect.same(ordinal(100), parent)
        case other => failure(s"Expected MissingInsideRetainedWindow, got $other")
      }
    )
  }

  test("resolveAll is all-or-nothing and chooses the lowest malformed ordinal deterministically") {
    val result = resolveAll(
      UnappliedSpendAction,
      Set(ordinal(49), ordinal(50), ordinal(75)),
      ordinal(100),
      retainedCount = 50,
      List(ordinal(75))
    )(identity)

    IO.pure(
      result match {
        case Left(OutsideRetainedWindow(_, required, _, _)) => expect.same(ordinal(49), required)
        case other                                          => failure(s"Expected deterministic lowest-ordinal failure, got $other")
      }
    )
  }

  test("cumulative processed history is derived from signed parent plus current unapplied state") {
    val previousView =
      GlobalSyncView(ordinal(80), Hash("parent"), io.constellationnetwork.schema.epoch.EpochProgress(NonNegLong.unsafeFrom(1L)))
    val result = ProcessedGlobalSnapshotHistory.derive(
      Some(previousView),
      SortedSet(ordinal(70), ordinal(80), ordinal(99)),
      SortedSet(ordinal(70), ordinal(80), ordinal(90)),
      ordinal(90)
    )

    IO.pure(
      result match {
        case Right(plan) =>
          expect.same(SortedSet(ordinal(70), ordinal(80)), plan.carried) &&
            expect.same(SortedSet(ordinal(90)), plan.newlyRequired) &&
            expect.same(SortedSet(ordinal(70), ordinal(80), ordinal(90)), plan.cumulative)
        case Left(error) => failure(error.getMessage)
      }
    )
  }

  test("unproven previously-visible history fails closed after restart") {
    val previousView =
      GlobalSyncView(ordinal(80), Hash("parent"), io.constellationnetwork.schema.epoch.EpochProgress(NonNegLong.unsafeFrom(1L)))
    val result = ProcessedGlobalSnapshotHistory.derive(
      Some(previousView),
      SortedSet(ordinal(80)),
      SortedSet(ordinal(70), ordinal(80), ordinal(90)),
      ordinal(90)
    )

    IO.pure(
      result match {
        case Left(ProcessedGlobalSnapshotHistory.ProcessedHistoryUnproven(ordinals)) =>
          expect.same(SortedSet(ordinal(70)), ordinals)
        case other => failure(s"Expected ProcessedHistoryUnproven, got $other")
      }
    )
  }

  test("acknowledged ordinals naturally disappear from the cumulative artifact") {
    val result = ProcessedGlobalSnapshotHistory.derive(
      Some(
        GlobalSyncView(ordinal(90), Hash("parent"), io.constellationnetwork.schema.epoch.EpochProgress(NonNegLong.unsafeFrom(1L)))
      ),
      SortedSet(ordinal(70), ordinal(80), ordinal(90)),
      SortedSet(ordinal(90)),
      ordinal(91)
    )

    IO.pure(expect.same(Right(ProcessedGlobalSnapshotHistory.Plan(SortedSet(ordinal(90)), SortedSet.empty)), result))
  }

  test("recovery marker is exact, excluded from payload, and reserved even in a mixed application artifact") {
    val ordinary = GlobalSnapshotsProcessed(SortedSet(ordinal(70), ordinal(80)))
    val mixed = GlobalSnapshotsProcessed(SortedSet(ordinal(70), SnapshotOrdinal.MaxValue))
    val artifacts = List(ordinary, ProcessedGlobalSnapshotHistory.Marker, mixed)

    IO.pure(
      expect(ProcessedGlobalSnapshotHistory.markerPresent(artifacts)) &&
        expect(ProcessedGlobalSnapshotHistory.containsReservedMarker(mixed)) &&
        expect.same(SortedSet(ordinal(70), ordinal(80)), ProcessedGlobalSnapshotHistory.payload(artifacts))
    )
  }
}
