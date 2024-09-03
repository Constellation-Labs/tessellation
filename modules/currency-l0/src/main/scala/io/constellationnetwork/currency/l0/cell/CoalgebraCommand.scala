package io.constellationnetwork.currency.l0.cell

import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent

sealed trait CoalgebraCommand

object CoalgebraCommand {
  case class ProcessCurrencySnapshotEvent(data: CurrencySnapshotEvent) extends CoalgebraCommand
}
