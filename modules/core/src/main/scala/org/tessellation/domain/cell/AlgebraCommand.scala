package org.tessellation.domain.cell

import org.tessellation.dag.domain.block.L1Output
import org.tessellation.domain.aci.StateChannelOutput
import org.tessellation.kernel.Ω
import org.tessellation.security.signature.Signed

sealed trait AlgebraCommand extends Ω

object AlgebraCommand {
  case class EnqueueStateChannelSnapshot(snapshot: StateChannelOutput) extends AlgebraCommand
  case class EnqueueDAGL1Data(data: Signed[L1Output]) extends AlgebraCommand
  case object NoAction extends AlgebraCommand
}
