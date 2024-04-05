package org.tessellation.currency.l0.cell

import org.tessellation.kernel.Ω
import org.tessellation.node.shared.snapshot.currency.CurrencySnapshotEvent

sealed trait AlgebraCommand extends Ω

object AlgebraCommand {
  case class EnqueueCurrencySnapshotEvent(data: CurrencySnapshotEvent) extends AlgebraCommand
  case object NoAction extends AlgebraCommand
}
