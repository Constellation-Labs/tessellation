package io.constellationnetwork.dag.l1.domain.consensus.block

import cats.Show

import io.constellationnetwork.kernel.Ω
import io.constellationnetwork.schema.block.Tips
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.round.RoundId
import io.constellationnetwork.schema.transaction.Transaction
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.Signature

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

sealed trait BlockConsensusInput extends Ω

object BlockConsensusInput {
  sealed trait OwnerBlockConsensusInput extends BlockConsensusInput
  case object OwnRoundTrigger extends OwnerBlockConsensusInput
  case object InspectionTrigger extends OwnerBlockConsensusInput

  @derive(encoder, decoder)
  sealed trait PeerBlockConsensusInput extends BlockConsensusInput {
    val senderId: PeerId
    val owner: PeerId
  }

  case class Proposal(
    roundId: RoundId,
    senderId: PeerId,
    owner: PeerId,
    facilitators: Set[PeerId],
    transactions: Set[Signed[Transaction]],
    tips: Tips
  ) extends PeerBlockConsensusInput

  case class BlockSignatureProposal(roundId: RoundId, senderId: PeerId, owner: PeerId, signature: Signature) extends PeerBlockConsensusInput

  case class CancelledBlockCreationRound(roundId: RoundId, senderId: PeerId, owner: PeerId, reason: CancellationReason)
      extends PeerBlockConsensusInput

  implicit def showBlockConsensusInput: Show[BlockConsensusInput] = {
    case OwnRoundTrigger   => "OwnRoundTrigger"
    case InspectionTrigger => "InspectionTrigger"
    case Proposal(roundId, senderId, _, _, txs, _) =>
      s"Proposal(roundId=${roundId.value.toString.take(8)}, senderId=${senderId.value.value.take(8)} txsCount=${txs.size})"
    case BlockSignatureProposal(roundId, senderId, _, _) =>
      s"BlockSignatureProposal(roundId=${roundId.value.toString.take(8)}, senderId=${senderId.value.value.take(8)})"
    case CancelledBlockCreationRound(roundId, senderId, _, reason) =>
      s"CancelledBlockCreationRound(roundId=${roundId.value.toString.take(8)}, senderId=${senderId.value.value.take(8)}, reason=$reason)"
  }
}
