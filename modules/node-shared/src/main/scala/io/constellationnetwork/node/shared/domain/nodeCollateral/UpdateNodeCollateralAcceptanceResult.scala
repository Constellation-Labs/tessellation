package io.constellationnetwork.node.shared.domain.nodeCollateral

import cats.data.NonEmptyChain

import scala.collection.immutable.SortedMap

import io.constellationnetwork.node.shared.domain.nodeCollateral.UpdateNodeCollateralValidator.UpdateNodeCollateralValidationError
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.nodeCollateral.UpdateNodeCollateral
import io.constellationnetwork.security.signature.Signed

case class UpdateNodeCollateralAcceptanceResult(
  acceptedCreates: SortedMap[Address, List[(Signed[UpdateNodeCollateral.Create], SnapshotOrdinal)]],
  notAcceptedCreates: List[(Signed[UpdateNodeCollateral.Create], NonEmptyChain[UpdateNodeCollateralValidationError])],
  acceptedWithdrawals: SortedMap[Address, List[(Signed[UpdateNodeCollateral.Withdraw], EpochProgress)]],
  notAcceptedWithdrawals: List[(Signed[UpdateNodeCollateral.Withdraw], NonEmptyChain[UpdateNodeCollateralValidationError])]
)
