package io.constellationnetwork.currency.swap

import cats.Show

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.round.RoundId
import io.constellationnetwork.schema.swap.AllowSpend
import io.constellationnetwork.security.Encodable
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.Signature

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

sealed trait ConsensusInput

object ConsensusInput {

  sealed trait OwnerConsensusInput extends ConsensusInput
  case object OwnRoundTrigger extends OwnerConsensusInput
  case object InspectionTrigger extends OwnerConsensusInput

  @derive(encoder, decoder)
  sealed trait PeerConsensusInput extends ConsensusInput {
    val senderId: PeerId
    val owner: PeerId
  }

  @derive(encoder, decoder)
  case class Proposal(
    roundId: RoundId,
    senderId: PeerId,
    owner: PeerId,
    facilitators: Set[PeerId],
    transactions: Set[Signed[AllowSpend]]
  ) extends PeerConsensusInput
      with Encodable[(RoundId, PeerId, PeerId, Set[PeerId], Set[Signed[AllowSpend]])] {
    override def toEncode = (roundId, senderId, owner, facilitators, transactions)
    override def jsonEncoder = implicitly
  }

  @derive(encoder, decoder)
  case class SignatureProposal(roundId: RoundId, senderId: PeerId, owner: PeerId, signature: Signature)
      extends PeerConsensusInput
      with Encodable[(RoundId, PeerId, PeerId, Signature)] {
    override def toEncode = (roundId, senderId, owner, signature)
    override def jsonEncoder = implicitly
  }

  @derive(encoder, decoder)
  case class CancelledCreationRound(roundId: RoundId, senderId: PeerId, owner: PeerId, reason: SwapCancellationReason)
      extends PeerConsensusInput

  implicit def showConsensusInput: Show[ConsensusInput] = {
    case OwnRoundTrigger   => "OwnRoundTrigger"
    case InspectionTrigger => "InspectionTrigger"
    case Proposal(roundId, senderId, _, _, transactions) =>
      s"Proposal(roundId=${roundId.value.toString
          .take(8)}, senderId=${senderId.value.value.take(8)}, transactionsCount=${transactions.size})"
    case SignatureProposal(roundId, senderId, _, _) =>
      s"SignatureProposal(roundId=${roundId.value.toString.take(8)}, senderId=${senderId.value.value.take(8)})"
    case CancelledCreationRound(roundId, senderId, _, reason) =>
      s"CancelledCreationRound(roundId=${roundId.value.toString.take(8)}, senderId=${senderId.value.value.take(8)}, reason=$reason)"
  }
}
