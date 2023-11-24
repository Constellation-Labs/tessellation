package org.tessellation.dag.l0.domain.cell

import org.tessellation.kernel.Ω
import org.tessellation.schema.Block
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelOutput

sealed trait AlgebraCommand extends Ω

object AlgebraCommand {
  case class EnqueueStateChannelSnapshot(snapshot: StateChannelOutput) extends AlgebraCommand
  case class EnqueueDAGL1Data(data: Signed[Block]) extends AlgebraCommand
  case object NoAction extends AlgebraCommand
}
