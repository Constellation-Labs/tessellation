package org.tessellation.currency.l0.cell

import org.tessellation.kernel.Ω
import org.tessellation.schema.Block
import org.tessellation.security.signature.Signed

sealed trait AlgebraCommand extends Ω

object AlgebraCommand {
  case class EnqueueL1BlockData(data: Signed[Block]) extends AlgebraCommand
  case object NoAction extends AlgebraCommand
}
