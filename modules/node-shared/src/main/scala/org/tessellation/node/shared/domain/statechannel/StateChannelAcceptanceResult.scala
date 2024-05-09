package org.tessellation.node.shared.domain.statechannel

import cats.data.NonEmptyList

import scala.collection.immutable.SortedMap

import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshot, CurrencySnapshotInfo}
import org.tessellation.node.shared.domain.statechannel.StateChannelAcceptanceResult.CurrencySnapshotWithState
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

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
