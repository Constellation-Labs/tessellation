package org.tessellation.dag.l1.domain.consensus.block

import cats.Show

import org.tessellation.kernel.Ω
import org.tessellation.schema.block.Tips
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.round.RoundId
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.Signature

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}

sealed trait BlockConsensusInput[+T <: Transaction] extends Ω

object BlockConsensusInput {
  sealed trait OwnerBlockConsensusInput extends BlockConsensusInput[Nothing]
  case object OwnRoundTrigger extends OwnerBlockConsensusInput
  case object InspectionTrigger extends OwnerBlockConsensusInput

  sealed trait PeerBlockConsensusInput[+T <: Transaction] extends BlockConsensusInput[T] {
    val senderId: PeerId
    val owner: PeerId
  }

  object PeerBlockConsensusInput {
    implicit def encoder[T <: Transaction: Encoder]: Encoder[PeerBlockConsensusInput[T]] = deriveEncoder
    implicit def decoder[T <: Transaction: Decoder]: Decoder[PeerBlockConsensusInput[T]] = deriveDecoder
  }

  case class Proposal[T <: Transaction](
    roundId: RoundId,
    senderId: PeerId,
    owner: PeerId,
    facilitators: Set[PeerId],
    transactions: Set[Signed[T]],
    tips: Tips
  ) extends PeerBlockConsensusInput[T]

  case class BlockSignatureProposal(roundId: RoundId, senderId: PeerId, owner: PeerId, signature: Signature)
      extends PeerBlockConsensusInput[Nothing]

  case class CancelledBlockCreationRound(roundId: RoundId, senderId: PeerId, owner: PeerId, reason: CancellationReason)
      extends PeerBlockConsensusInput[Nothing]

  implicit def showBlockConsensusInput[T <: Transaction]: Show[BlockConsensusInput[T]] = {
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
