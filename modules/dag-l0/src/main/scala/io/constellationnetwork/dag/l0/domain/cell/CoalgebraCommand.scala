package io.constellationnetwork.dag.l0.domain.cell

import io.constellationnetwork.schema.Block
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.StateChannelOutput

sealed trait CoalgebraCommand

object CoalgebraCommand {
  case class ProcessDAGL1(data: Signed[Block]) extends CoalgebraCommand
  case class ProcessStateChannelSnapshot(snapshot: StateChannelOutput) extends CoalgebraCommand
  case class ProcessUpdateNodeParameters(updateNodeParameters: Signed[UpdateNodeParameters]) extends CoalgebraCommand
}
