package io.constellationnetwork.node.shared.config

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.node.RewardFraction
import io.constellationnetwork.schema.peer.PeerId

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosLong

final case class DelegatedRewardsEra(
  activationOrdinal: SnapshotOrdinal,
  rewardValidators: SortedMap[SnapshotOrdinal, SortedSet[PeerId]],
  epochsPerYear: PosLong = PosLong(485502L)
) {
  def isActive(ordinal: SnapshotOrdinal): Boolean =
    activationOrdinal != SnapshotOrdinal.unsafeApply(Long.MaxValue) && ordinal.value.value >= activationOrdinal.value.value

  def collateralAt(ordinal: SnapshotOrdinal, legacyAmount: Amount): Amount =
    if (isActive(ordinal)) DelegatedRewardsEra.MinimumNodeCollateral else legacyAmount

  def validatorsAt(ordinal: SnapshotOrdinal): SortedSet[PeerId] =
    rewardValidators.iterator.takeWhile { case (effectiveFrom, _) => effectiveFrom.value.value <= ordinal.value.value }.toList.lastOption
      .fold(SortedSet.empty[PeerId])(_._2)
}

object DelegatedRewardsEra {
  val Disabled: DelegatedRewardsEra = DelegatedRewardsEra(SnapshotOrdinal.unsafeApply(Long.MaxValue), SortedMap.empty)
  val Commission: RewardFraction = RewardFraction(10_000_000)
  val MinimumNodeCollateral: Amount = Amount(1_000_000_00000000L)
  val MinimumRegistrationBalance: Amount = MinimumNodeCollateral
}
