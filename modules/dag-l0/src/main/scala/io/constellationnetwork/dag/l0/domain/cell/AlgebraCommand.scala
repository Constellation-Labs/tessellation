package io.constellationnetwork.dag.l0.domain.cell

import io.constellationnetwork.dag.l0.domain.delegatedStake.DelegatedStakeOutput
import io.constellationnetwork.dag.l0.domain.nodeCollateral.NodeCollateralOutput
import io.constellationnetwork.kernel.Ω
import io.constellationnetwork.schema.Block
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.StateChannelOutput

sealed trait AlgebraCommand extends Ω

object AlgebraCommand {
  case class EnqueueStateChannelSnapshot(snapshot: StateChannelOutput) extends AlgebraCommand
  case class EnqueueDAGL1Data(data: Signed[Block]) extends AlgebraCommand
  case class EnqueueUpdateNodeParameters(data: Signed[UpdateNodeParameters]) extends AlgebraCommand
  case class EnqueueDelegatedStake(data: DelegatedStakeOutput) extends AlgebraCommand
  case class EnqueueNodeCollateral(data: NodeCollateralOutput) extends AlgebraCommand
  case object NoAction extends AlgebraCommand
}
