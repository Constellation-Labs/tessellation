package io.constellationnetwork.node.shared.config

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.security.hex.Hex

import eu.timepit.refined.auto._
import weaver.SimpleIOSuite

object DelegatedRewardsEraSuite extends SimpleIOSuite {
  private val originalValidator = Id(Hex("1234567890abcdef")).toPeerId
  private val replacementValidator = Id(Hex("abcdef1234567890")).toPeerId
  private val retainedValidator = Id(Hex("abcdefabcdef1234")).toPeerId

  pureTest("the disabled era stays disabled even at the maximum representable epoch") {
    val disabled = DelegatedRewardsEra.Disabled
    expect(!disabled.isActive(EpochProgress.MinValue), "missing configuration must not activate at genesis")
      .and(expect(!disabled.isActive(EpochProgress(100L)), "ordinary snapshots retain legacy rewards"))
      .and(expect(!disabled.isActive(EpochProgress.MaxValue), "the disabled sentinel is never an activation event"))
      .and(
        expect(disabled.validatorsAt(SnapshotOrdinal.unsafeApply(Long.MaxValue)).isEmpty, "disabled configuration supplies no validators")
      )
  }

  pureTest("an explicit zero activation enables the new era from genesis") {
    val genesis = EpochProgress.MinValue
    val era = DelegatedRewardsEra(genesis, SortedMap(SnapshotOrdinal.MinValue -> SortedSet(originalValidator)))

    expect(era.isActive(genesis), "zero must be explicit for active-from-genesis behavior")
      .and(expect(era.isActive(EpochProgress(1L)), "the first successor uses the same era"))
      .and(
        expect(
          era.validatorsAt(SnapshotOrdinal.MinValue) == SortedSet(originalValidator),
          "the genesis membership is available immediately"
        )
      )
  }

  pureTest("a future activation switches behavior exactly at A and remains active at A plus one") {
    val activation = EpochProgress(100L)
    val era = DelegatedRewardsEra(activation, SortedMap(SnapshotOrdinal.MinValue -> SortedSet(originalValidator)))

    expect(!era.isActive(EpochProgress(99L)), "A minus one must preserve historical rewards")
      .and(expect(era.isActive(activation), "A is the first epoch in the new era"))
      .and(expect(era.isActive(EpochProgress(101L)), "A plus one continues the new era"))
  }

  pureTest("quarterly membership replacement preserves the exact historical membership on replay") {
    val initial = SortedSet(originalValidator, retainedValidator)
    val revised = SortedSet(replacementValidator, retainedValidator)
    val era = DelegatedRewardsEra(
      EpochProgress(100L),
      SortedMap(SnapshotOrdinal.unsafeApply(100L) -> initial, SnapshotOrdinal.unsafeApply(200L) -> revised)
    )

    expect(era.validatorsAt(SnapshotOrdinal.unsafeApply(100L)) == initial, "initial snapshots retain the original operators")
      .and(
        expect(
          era.validatorsAt(SnapshotOrdinal.unsafeApply(199L)) == initial,
          "the last snapshot before revision still uses original membership"
        )
      )
      .and(expect(era.validatorsAt(SnapshotOrdinal.unsafeApply(200L)) == revised, "revision takes effect exactly at its recorded ordinal"))
      .and(
        expect(
          era.validatorsAt(SnapshotOrdinal.unsafeApply(201L)) == revised,
          "removed operators must not remain eligible after replacement"
        )
      )
  }

  pureTest("epoch activation and snapshot-ordinal membership have independent boundaries") {
    val era = DelegatedRewardsEra(
      EpochProgress(7L),
      SortedMap(SnapshotOrdinal.unsafeApply(1_000_000L) -> SortedSet(originalValidator))
    )
    expect(!era.isActive(EpochProgress(6L)), "a high snapshot ordinal cannot activate an earlier epoch")
      .and(expect(era.isActive(EpochProgress(7L)), "the policy activates by epoch"))
      .and(expect(era.validatorsAt(SnapshotOrdinal.unsafeApply(999_999L)).isEmpty, "membership does not activate by epoch"))
      .and(
        expect(
          era.validatorsAt(SnapshotOrdinal.unsafeApply(1_000_000L)) == SortedSet(originalValidator),
          "membership uses ordinal independently of activation epoch"
        )
      )
  }

  pureTest("a future validator list cannot grant rewards before its effective ordinal") {
    val era = DelegatedRewardsEra(
      EpochProgress(100L),
      SortedMap(
        SnapshotOrdinal.unsafeApply(120L) -> SortedSet(originalValidator),
        SnapshotOrdinal.unsafeApply(200L) -> SortedSet(replacementValidator)
      )
    )

    expect(era.validatorsAt(SnapshotOrdinal.unsafeApply(99L)).isEmpty, "no membership applies before the first scheduled list")
      .and(
        expect(
          era.validatorsAt(SnapshotOrdinal.unsafeApply(100L)).isEmpty,
          "an earlier snapshot ordinal must not apply a future list early"
        )
      )
      .and(expect(era.validatorsAt(SnapshotOrdinal.unsafeApply(119L)).isEmpty, "the first future list remains inactive until its boundary"))
      .and(
        expect(
          era.validatorsAt(SnapshotOrdinal.unsafeApply(120L)) == SortedSet(originalValidator),
          "the first list applies at its own boundary"
        )
      )
  }

  pureTest("an explicit empty revision clears membership instead of restoring an earlier list") {
    val era = DelegatedRewardsEra(
      EpochProgress(100L),
      SortedMap(SnapshotOrdinal.unsafeApply(100L) -> SortedSet(originalValidator), SnapshotOrdinal.unsafeApply(200L) -> SortedSet.empty)
    )

    expect(era.validatorsAt(SnapshotOrdinal.unsafeApply(199L)) == SortedSet(originalValidator), "the previous list remains historical")
      .and(expect(era.validatorsAt(SnapshotOrdinal.unsafeApply(200L)).isEmpty, "empty replacement must not fall back to an older list"))
      .and(expect(era.validatorsAt(SnapshotOrdinal.unsafeApply(201L)).isEmpty, "empty replacement remains effective"))
  }
  pureTest("collateral preserves configured historical amounts and activates 1M exactly at the era boundary") {
    val era = DelegatedRewardsEra(EpochProgress(100L), SortedMap.empty)
    val legacy = Amount(250_000_00000000L)
    expect
      .same(legacy, era.collateralAt(EpochProgress(99L), legacy))
      .and(expect.same(DelegatedRewardsEra.MinimumNodeCollateral, era.collateralAt(EpochProgress(100L), legacy)))
      .and(expect.same(DelegatedRewardsEra.MinimumNodeCollateral, era.collateralAt(EpochProgress(101L), legacy)))
      .and(expect.same(legacy, DelegatedRewardsEra.Disabled.collateralAt(EpochProgress(100L), legacy)))
      .and(expect.same(Amount.empty, DelegatedRewardsEra.Disabled.collateralAt(EpochProgress(100L), Amount.empty)))
  }

}
