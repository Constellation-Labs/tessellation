package org.tessellation.dag.l1.domain.consensus.block

import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput.{
  BlockProposal,
  CancelledBlockCreationRound,
  Proposal
}

sealed trait CoalgebraCommand

object CoalgebraCommand {
  case object StartOwnRound extends CoalgebraCommand
  case class ProcessProposal(proposal: Proposal) extends CoalgebraCommand
  case class ProcessBlockProposal(blockProposal: BlockProposal) extends CoalgebraCommand
  case class ProcessCancellation(cancellation: CancelledBlockCreationRound) extends CoalgebraCommand
}