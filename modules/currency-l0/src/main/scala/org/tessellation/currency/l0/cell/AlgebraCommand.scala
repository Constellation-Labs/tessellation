package org.tessellation.currency.l0.cell

import org.tessellation.currency.schema.currency.CurrencyBlock
import org.tessellation.kernel.Ω
import org.tessellation.security.signature.Signed

sealed trait AlgebraCommand extends Ω

object AlgebraCommand {
  case class EnqueueL1BlockData(data: Signed[CurrencyBlock]) extends AlgebraCommand
  case object NoAction extends AlgebraCommand
}
