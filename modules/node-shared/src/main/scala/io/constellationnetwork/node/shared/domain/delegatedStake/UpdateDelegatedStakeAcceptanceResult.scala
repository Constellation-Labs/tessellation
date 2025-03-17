package io.constellationnetwork.node.shared.domain.delegatedStake

import cats.data.NonEmptyChain

import scala.collection.immutable.SortedMap

import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeValidator.UpdateDelegatedStakeValidationError
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.delegatedStake.UpdateDelegatedStake
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.security.signature.Signed

case class UpdateDelegatedStakeAcceptanceResult(
  acceptedCreates: SortedMap[Address, List[(Signed[UpdateDelegatedStake.Create], SnapshotOrdinal)]],
  notAcceptedCreates: List[(Signed[UpdateDelegatedStake.Create], NonEmptyChain[UpdateDelegatedStakeValidationError])],
  acceptedWithdrawals: SortedMap[Address, List[(Signed[UpdateDelegatedStake.Withdraw], EpochProgress)]],
  notAcceptedWithdrawals: List[(Signed[UpdateDelegatedStake.Withdraw], NonEmptyChain[UpdateDelegatedStakeValidationError])]
)
