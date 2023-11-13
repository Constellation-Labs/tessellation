package org.tessellation.currency.l0.cell

import org.tessellation.currency.l0.snapshot.CurrencySnapshotEvent
import org.tessellation.kernel.Ω

sealed trait AlgebraCommand extends Ω

object AlgebraCommand {
  case class EnqueueL1BlockData(data: CurrencySnapshotEvent) extends AlgebraCommand
  case object NoAction extends AlgebraCommand
}
