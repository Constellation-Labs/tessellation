package org.tessellation.dag.l1.domain.consensus.block

import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput.{BlockSignatureProposal, CancelledBlockCreationRound, Proposal}

sealed trait CoalgebraCommand

object CoalgebraCommand {
  case object StartOwnRound extends CoalgebraCommand
  case object InspectConsensuses extends CoalgebraCommand
  case class ProcessProposal(proposal: Proposal) extends CoalgebraCommand
  case class ProcessBlockSignatureProposal(blockSignatureProposal: BlockSignatureProposal) extends CoalgebraCommand
  case class ProcessCancellation(cancellation: CancelledBlockCreationRound) extends CoalgebraCommand
}
