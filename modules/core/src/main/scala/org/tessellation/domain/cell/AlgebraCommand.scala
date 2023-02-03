package org.tessellation.domain.cell

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.kernel.Ω
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelOutput

sealed trait AlgebraCommand extends Ω

object AlgebraCommand {
  case class EnqueueStateChannelSnapshot(snapshot: StateChannelOutput) extends AlgebraCommand
  case class EnqueueDAGL1Data(data: Signed[DAGBlock]) extends AlgebraCommand
  case object NoAction extends AlgebraCommand
}
