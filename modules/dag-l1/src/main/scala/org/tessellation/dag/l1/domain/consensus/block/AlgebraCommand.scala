package org.tessellation.dag.l1.domain.consensus.block

import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput.{BlockSignatureProposal, CancelledBlockCreationRound, Proposal}
import org.tessellation.kernel.Ω

sealed trait AlgebraCommand extends Ω

object AlgebraCommand {
  case class PersistInitialOwnRoundData(roundData: RoundData) extends AlgebraCommand
  case class PersistInitialPeerRoundData(roundData: RoundData, peerProposal: Proposal) extends AlgebraCommand
  case class PersistProposal(proposal: Proposal) extends AlgebraCommand
  case class PersistBlockSignatureProposal(blockSignatureProposal: BlockSignatureProposal) extends AlgebraCommand
  case class InformAboutInabilityToParticipate(proposal: Proposal, reason: CancellationReason) extends AlgebraCommand
  case class PersistCancellationResult(cancellation: CancelledBlockCreationRound) extends AlgebraCommand
  case class InformAboutRoundStartFailure(message: String) extends AlgebraCommand
  case class CancelTimedOutRounds(toCancel: Set[Proposal]) extends AlgebraCommand
  case object NoAction extends AlgebraCommand
}
