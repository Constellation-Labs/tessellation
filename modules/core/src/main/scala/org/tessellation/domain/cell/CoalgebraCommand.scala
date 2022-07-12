package org.tessellation.domain.cell

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.domain.aci.StateChannelOutput
import org.tessellation.security.signature.Signed

sealed trait CoalgebraCommand

object CoalgebraCommand {
  case class ProcessDAGL1(data: Signed[DAGBlock]) extends CoalgebraCommand
  case class ProcessStateChannelSnapshot(snapshot: StateChannelOutput) extends CoalgebraCommand
}
