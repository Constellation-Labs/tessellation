package org.tessellation.dag.l1.domain.consensus.block

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.dag.l1.domain.block.Tips
import org.tessellation.dag.l1.domain.consensus.round.RoundId
import org.tessellation.kernel.Ω
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.signature.Signed

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

sealed trait BlockConsensusInput extends Ω

object BlockConsensusInput {
  sealed trait OwnerBlockConsensusInput extends BlockConsensusInput

  @derive(encoder, decoder)
  sealed trait PeerBlockConsensusInput extends BlockConsensusInput {
    val senderId: PeerId
    val owner: PeerId
  }
  case object OwnRoundTrigger extends OwnerBlockConsensusInput
  case object InspectionTrigger extends OwnerBlockConsensusInput
  case class Proposal(
    roundId: RoundId,
    senderId: PeerId,
    owner: PeerId,
    facilitators: Set[PeerId],
    transactions: Set[Signed[Transaction]],
    tips: Tips
  ) extends PeerBlockConsensusInput
  case class BlockProposal(roundId: RoundId, senderId: PeerId, owner: PeerId, signedBlock: Signed[DAGBlock])
      extends PeerBlockConsensusInput
  case class CancelledBlockCreationRound(roundId: RoundId, senderId: PeerId, owner: PeerId, reason: CancellationReason)
      extends PeerBlockConsensusInput
}
