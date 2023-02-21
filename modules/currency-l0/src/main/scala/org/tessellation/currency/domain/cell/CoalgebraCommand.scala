package org.tessellation.currency.domain.cell

import org.tessellation.currency.schema.currency.CurrencyBlock
import org.tessellation.security.signature.Signed

sealed trait CoalgebraCommand

object CoalgebraCommand {
  case class ProcessL1Block(data: Signed[CurrencyBlock]) extends CoalgebraCommand
}
