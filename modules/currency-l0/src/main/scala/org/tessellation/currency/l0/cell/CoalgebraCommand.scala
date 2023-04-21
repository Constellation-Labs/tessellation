package org.tessellation.currency.l0.cell

import org.tessellation.schema.Block
import org.tessellation.security.signature.Signed

sealed trait CoalgebraCommand

object CoalgebraCommand {
  case class ProcessL1Block(data: Signed[Block]) extends CoalgebraCommand
}
