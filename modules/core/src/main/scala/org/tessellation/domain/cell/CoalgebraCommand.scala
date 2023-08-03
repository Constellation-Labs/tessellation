package org.tessellation.domain.cell

import org.tessellation.schema.Block
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelOutput

sealed trait CoalgebraCommand

object CoalgebraCommand {
  case class ProcessDAGL1(data: Signed[Block]) extends CoalgebraCommand
  case class ProcessStateChannelSnapshot(snapshot: StateChannelOutput) extends CoalgebraCommand
}
