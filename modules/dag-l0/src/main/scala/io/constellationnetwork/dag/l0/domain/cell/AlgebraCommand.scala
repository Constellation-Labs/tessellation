package io.constellationnetwork.dag.l0.domain.cell

import io.constellationnetwork.kernel.Ω
import io.constellationnetwork.schema.Block
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.StateChannelOutput

sealed trait AlgebraCommand extends Ω

object AlgebraCommand {
  case class EnqueueStateChannelSnapshot(snapshot: StateChannelOutput) extends AlgebraCommand
  case class EnqueueDAGL1Data(data: Signed[Block]) extends AlgebraCommand
  case object NoAction extends AlgebraCommand
}
