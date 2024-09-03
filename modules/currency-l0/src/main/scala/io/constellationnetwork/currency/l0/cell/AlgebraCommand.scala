package io.constellationnetwork.currency.l0.cell

import io.constellationnetwork.kernel.Ω
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent

sealed trait AlgebraCommand extends Ω

object AlgebraCommand {
  case class EnqueueCurrencySnapshotEvent(data: CurrencySnapshotEvent) extends AlgebraCommand
  case object NoAction extends AlgebraCommand
}
