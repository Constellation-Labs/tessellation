package org.tessellation.currency.l0.cell

import org.tessellation.currency.l0.snapshot.CurrencySnapshotEvent

sealed trait CoalgebraCommand

object CoalgebraCommand {
  case class ProcessL1Block(data: CurrencySnapshotEvent) extends CoalgebraCommand
}
