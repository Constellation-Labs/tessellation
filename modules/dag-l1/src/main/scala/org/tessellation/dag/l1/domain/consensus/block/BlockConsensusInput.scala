package org.tessellation.dag.l1.domain.consensus.block

import cats.Show
import cats.syntax.show._

import org.tessellation.kernel.Ω
import org.tessellation.schema.block.Tips
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.round.RoundId
import org.tessellation.schema.swap.AllowSpend
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.Signature

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
    allowSpendTransactions: Set[Signed[AllowSpend]],
    tips: Tips
  ) extends PeerBlockConsensusInput

  case class BlockSignatureProposal(roundId: RoundId, senderId: PeerId, owner: PeerId, signature: Signature) extends PeerBlockConsensusInput

  case class CancelledBlockCreationRound(roundId: RoundId, senderId: PeerId, owner: PeerId, reason: CancellationReason)
      extends PeerBlockConsensusInput

  implicit def showBlockConsensusInput: Show[BlockConsensusInput] = {
    case OwnRoundTrigger   => "OwnRoundTrigger"
    case InspectionTrigger => "InspectionTrigger"
    case Proposal(roundId, senderId, _, _, txs, allowSpendTxs, _) =>
      implicit val peerIdShow = PeerId.shortShow
      implicit val roundIdShow = RoundId.shortShow
      s"Proposal(roundId=${roundId.show}, senderId=${senderId.show} txsCount=${txs.size}, allowSpendTxsCount=${allowSpendTxs.size})"
    case BlockSignatureProposal(roundId, senderId, _, _) =>
      implicit val peerIdShow = PeerId.shortShow
      implicit val roundIdShow = RoundId.shortShow
      s"BlockSignatureProposal(roundId=${roundId.show}, senderId=${senderId.show})"
    case CancelledBlockCreationRound(roundId, senderId, _, reason) =>
      implicit val peerIdShow = PeerId.shortShow
      implicit val roundIdShow = RoundId.shortShow
      s"CancelledBlockCreationRound(roundId=${roundId.show}, senderId=${senderId.show}, reason=$reason)"
  }
}
