package io.constellationnetwork.node.shared.domain.statechannel

import cats.data.NonEmptyList

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelAcceptanceResult.CurrencySnapshotWithState
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import derevo.cats.eqv
import derevo.derive

@derive(eqv)
case class StateChannelAcceptanceResult(
  accepted: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
  calculatedCurrencyState: SortedMap[Address, CurrencySnapshotWithState],
  returned: Set[StateChannelOutput],
  balanceUpdate: Map[Address, Balance]
)

object StateChannelAcceptanceResult {

  type CurrencySnapshotWithState = Either[Signed[CurrencySnapshot], (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]
}
