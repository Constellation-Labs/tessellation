package io.constellationnetwork.currency.schema

import cats.effect.IO

import scala.collection.immutable.SortedSet

import io.constellationnetwork.schema.SnapshotOrdinal

import weaver.SimpleIOSuite

object CurrencySnapshotSemanticsSuite extends SimpleIOSuite {
  private def ordinal(value: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(value)

  test("legacy history remains byte-compatible before activation") {
    IO.pure(
      expect.same(
        CurrencySnapshotSemantics.LegacyVersion,
        CurrencySnapshotSemantics.nextVersion(
          CurrencySnapshotSemantics.LegacyVersion,
          ordinal(99L),
          ordinal(100L),
          transitionHistoryProven = true
        )
      )
    )
  }

  test("the exact global activation boundary selects deterministic history") {
    IO.pure(
      expect.same(
        CurrencySnapshotSemantics.DeterministicHistoryVersion,
        CurrencySnapshotSemantics.nextVersion(
          CurrencySnapshotSemantics.LegacyVersion,
          ordinal(100L),
          ordinal(100L),
          transitionHistoryProven = true
        )
      )
    )
  }

  test("an absent MaxValue activation stays dormant even at a MaxValue reference") {
    IO.pure(
      expect.same(
        CurrencySnapshotSemantics.LegacyVersion,
        CurrencySnapshotSemantics.nextVersion(
          CurrencySnapshotSemantics.LegacyVersion,
          SnapshotOrdinal.MaxValue,
          SnapshotOrdinal.MaxValue,
          transitionHistoryProven = true
        )
      )
    )
  }

  test("unproven legacy processed history delays rather than guesses the transition") {
    IO.pure(
      expect.same(
        CurrencySnapshotSemantics.LegacyVersion,
        CurrencySnapshotSemantics.nextVersion(
          CurrencySnapshotSemantics.LegacyVersion,
          ordinal(101L),
          ordinal(100L),
          transitionHistoryProven = false
        )
      )
    )
  }

  test("a deterministic-history lineage never downgrades") {
    IO.pure(
      expect.same(
        CurrencySnapshotSemantics.DeterministicHistoryVersion,
        CurrencySnapshotSemantics.nextVersion(
          CurrencySnapshotSemantics.DeterministicHistoryVersion,
          ordinal(1L),
          ordinal(100L),
          transitionHistoryProven = false
        )
      )
    )
  }

  test("only unresolved history visible at the selected Global L0 view delays transition") {
    val selected = ordinal(100L)

    IO.pure(
      expect(!CurrencySnapshotSemantics.legacyHistoryResolvedThrough(SortedSet(ordinal(99L)), selected)) &&
        expect(!CurrencySnapshotSemantics.legacyHistoryResolvedThrough(SortedSet(ordinal(100L)), selected)) &&
        expect(CurrencySnapshotSemantics.legacyHistoryResolvedThrough(SortedSet(ordinal(101L)), selected))
    )
  }
}
