package io.constellationnetwork.node.shared.infrastructure.snapshot

import scala.collection.immutable.SortedSet

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotStateProof}
import io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSyncView
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.{SnapshotOrdinal, SnapshotTips}
import io.constellationnetwork.security.hash.Hash

import weaver.SimpleIOSuite

object CurrencySnapshotValidatorSuite extends SimpleIOSuite {

  private def gsv(ordinal: Long): GlobalSyncView =
    GlobalSyncView(SnapshotOrdinal.unsafeApply(ordinal), Hash.empty, EpochProgress.MinValue)

  private def snapshot(
    ordinal: SnapshotOrdinal = SnapshotOrdinal.MinValue,
    globalSyncView: Option[GlobalSyncView]
  ): CurrencyIncrementalSnapshot =
    CurrencyIncrementalSnapshot(
      ordinal = ordinal,
      height = Height.MinValue,
      subHeight = SubHeight.MinValue,
      lastSnapshotHash = Hash.empty,
      blocks = SortedSet.empty,
      rewards = SortedSet.empty,
      tips = SnapshotTips(SortedSet.empty, SortedSet.empty),
      stateProof = CurrencySnapshotStateProof(Hash.empty, Hash.empty, None, None, None, None, None, None, None),
      epochProgress = EpochProgress.MinValue,
      dataApplication = None,
      messages = None,
      globalSnapshotSyncs = None,
      feeTransactions = None,
      artifacts = None,
      allowSpendBlocks = None,
      tokenLockBlocks = None,
      globalSyncView = globalSyncView
    )

  // #1 regression: when a signed currency snapshot is validated while the GL0 cache has advanced, the
  // re-validator recreates a later globalSyncView. That field is signed and live-derived, so a difference in
  // it alone must NOT fail validation (it is pinned to the expected value). Without the pin, currency/spend
  // metagraph snapshots were reject-and-skipped from global state and never finalized.
  pureTest("matchesExpected ignores a differing globalSyncView (signed field pinned)") {
    val expected = snapshot(globalSyncView = Some(gsv(2)))
    val recreatedLaterView = snapshot(globalSyncView = Some(gsv(3))) // differs ONLY in globalSyncView
    expect(CurrencySnapshotValidator.matchesExpected(recreatedLaterView, expected))
  }

  pureTest("pinExpectedGlobalSyncView returns the signed artifact's trusted globalSyncView") {
    val expected = snapshot(globalSyncView = Some(gsv(2)))
    val recreatedLaterView = snapshot(globalSyncView = Some(gsv(3)))
    val pinned = CurrencySnapshotValidator.pinExpectedGlobalSyncView(recreatedLaterView, expected)

    expect.all(
      pinned.globalSyncView == expected.globalSyncView,
      pinned == expected
    )
  }

  pureTest("matchesExpected still fails on a real content difference") {
    val expected = snapshot(ordinal = SnapshotOrdinal.MinValue, globalSyncView = Some(gsv(2)))
    val realDiff = snapshot(ordinal = SnapshotOrdinal.unsafeApply(1L), globalSyncView = Some(gsv(2)))
    expect(!CurrencySnapshotValidator.matchesExpected(realDiff, expected))
  }

  pureTest("matchesExpected does not let a globalSyncView diff mask a real difference") {
    val expected = snapshot(ordinal = SnapshotOrdinal.MinValue, globalSyncView = Some(gsv(2)))
    val both = snapshot(ordinal = SnapshotOrdinal.unsafeApply(1L), globalSyncView = Some(gsv(3)))
    expect(!CurrencySnapshotValidator.matchesExpected(both, expected))
  }
}
