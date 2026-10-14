package io.constellationnetwork.node.shared.config

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.security.hex.Hex

import eu.timepit.refined.auto._
import weaver.SimpleIOSuite

object DelegatedRewardsEraSuite extends SimpleIOSuite {
  private val originalValidator = Id(Hex("1234567890abcdef")).toPeerId
  private val replacementValidator = Id(Hex("abcdef1234567890")).toPeerId
  private val retainedValidator = Id(Hex("abcdefabcdef1234")).toPeerId

  pureTest("the disabled era stays disabled even at the maximum representable ordinal") {
    val disabled = DelegatedRewardsEra.Disabled
    expect(!disabled.isActive(SnapshotOrdinal.MinValue), "missing configuration must not activate at genesis")
      .and(expect(!disabled.isActive(SnapshotOrdinal.unsafeApply(100L)), "ordinary snapshots retain legacy rewards"))
      .and(expect(!disabled.isActive(SnapshotOrdinal.unsafeApply(Long.MaxValue)), "the disabled sentinel is never an activation event"))
      .and(
        expect(disabled.validatorsAt(SnapshotOrdinal.unsafeApply(Long.MaxValue)).isEmpty, "disabled configuration supplies no validators")
      )
  }

  pureTest("an explicit zero activation enables the new era from genesis") {
    val genesis = SnapshotOrdinal.MinValue
    val era = DelegatedRewardsEra(genesis, SortedMap(genesis -> SortedSet(originalValidator)))

    expect(era.isActive(genesis), "zero must be explicit for active-from-genesis behavior")
      .and(expect(era.isActive(SnapshotOrdinal.unsafeApply(1L)), "the first successor uses the same era"))
      .and(expect(era.validatorsAt(genesis) == SortedSet(originalValidator), "the genesis membership is available immediately"))
  }

  pureTest("a future activation switches behavior exactly at A and remains active at A plus one") {
    val activation = SnapshotOrdinal.unsafeApply(100L)
    val era = DelegatedRewardsEra(activation, SortedMap(activation -> SortedSet(originalValidator)))

    expect(!era.isActive(SnapshotOrdinal.unsafeApply(99L)), "A minus one must preserve historical rewards")
      .and(expect(era.isActive(activation), "A is the first snapshot in the new era"))
      .and(expect(era.isActive(SnapshotOrdinal.unsafeApply(101L)), "A plus one continues the new era"))
  }

  pureTest("quarterly membership replacement preserves the exact historical membership on replay") {
    val initial = SortedSet(originalValidator, retainedValidator)
    val revised = SortedSet(replacementValidator, retainedValidator)
    val era = DelegatedRewardsEra(
      SnapshotOrdinal.unsafeApply(100L),
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

  pureTest("a future validator list cannot grant rewards before its effective ordinal") {
    val era = DelegatedRewardsEra(
      SnapshotOrdinal.unsafeApply(100L),
      SortedMap(
        SnapshotOrdinal.unsafeApply(120L) -> SortedSet(originalValidator),
        SnapshotOrdinal.unsafeApply(200L) -> SortedSet(replacementValidator)
      )
    )

    expect(era.validatorsAt(SnapshotOrdinal.unsafeApply(99L)).isEmpty, "no membership applies before the first scheduled list")
      .and(expect(era.validatorsAt(SnapshotOrdinal.unsafeApply(100L)).isEmpty, "activating the era must not apply a future list early"))
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
      SnapshotOrdinal.unsafeApply(100L),
      SortedMap(SnapshotOrdinal.unsafeApply(100L) -> SortedSet(originalValidator), SnapshotOrdinal.unsafeApply(200L) -> SortedSet.empty)
    )

    expect(era.validatorsAt(SnapshotOrdinal.unsafeApply(199L)) == SortedSet(originalValidator), "the previous list remains historical")
      .and(expect(era.validatorsAt(SnapshotOrdinal.unsafeApply(200L)).isEmpty, "empty replacement must not fall back to an older list"))
      .and(expect(era.validatorsAt(SnapshotOrdinal.unsafeApply(201L)).isEmpty, "empty replacement remains effective"))
  }
  pureTest("collateral preserves configured historical amounts and activates 1M exactly at the era boundary") {
    val era = DelegatedRewardsEra(SnapshotOrdinal.unsafeApply(100L), SortedMap.empty)
    val legacy = Amount(250_000_00000000L)
    expect
      .same(legacy, era.collateralAt(SnapshotOrdinal.unsafeApply(99L), legacy))
      .and(expect.same(DelegatedRewardsEra.MinimumNodeCollateral, era.collateralAt(SnapshotOrdinal.unsafeApply(100L), legacy)))
      .and(expect.same(DelegatedRewardsEra.MinimumNodeCollateral, era.collateralAt(SnapshotOrdinal.unsafeApply(101L), legacy)))
      .and(expect.same(legacy, DelegatedRewardsEra.Disabled.collateralAt(SnapshotOrdinal.unsafeApply(100L), legacy)))
      .and(expect.same(Amount.empty, DelegatedRewardsEra.Disabled.collateralAt(SnapshotOrdinal.unsafeApply(100L), Amount.empty)))
  }

}
