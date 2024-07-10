package io.constellationnetwork.currency.dataApplication

import cats.Show
import cats.data.NonEmptyList

import io.constellationnetwork.currency.dataApplication.DataTransaction.DataTransactions
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.round.RoundId
import io.constellationnetwork.security.Encodable
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.signature.Signature

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}

sealed trait ConsensusInput

object ConsensusInput {

  sealed trait OwnerConsensusInput extends ConsensusInput
  case object OwnRoundTrigger extends OwnerConsensusInput
  case object InspectionTrigger extends OwnerConsensusInput

  sealed trait PeerConsensusInput extends ConsensusInput {
    val senderId: PeerId
    val owner: PeerId
  }

  object PeerConsensusInput {
    def encoder(implicit e: Encoder[DataTransaction]): Encoder[PeerConsensusInput] = deriveEncoder
    def decoder(implicit d: Decoder[DataTransaction]): Decoder[PeerConsensusInput] = deriveDecoder
  }

  case class Proposal(
    roundId: RoundId,
    senderId: PeerId,
    owner: PeerId,
    facilitators: Set[PeerId],
    dataUpdates: Set[DataTransactions],
    dataHashes: Set[NonEmptyList[Hash]]
  ) extends PeerConsensusInput
      with Encodable[(RoundId, PeerId, PeerId, Set[PeerId], Set[NonEmptyList[Hash]])] {
    override def toEncode = (roundId, senderId, owner, facilitators, dataHashes)
    override def jsonEncoder = implicitly
  }

  object Proposal {
    def encoder(implicit e: Encoder[DataTransaction]): Encoder[Proposal] = deriveEncoder
    def decoder(implicit d: Decoder[DataTransaction]): Decoder[Proposal] = deriveDecoder
  }

  @derive(encoder, decoder)
  case class SignatureProposal(roundId: RoundId, senderId: PeerId, owner: PeerId, signature: Signature) extends PeerConsensusInput

  @derive(encoder, decoder)
  case class CancelledCreationRound(roundId: RoundId, senderId: PeerId, owner: PeerId, reason: DataCancellationReason)
      extends PeerConsensusInput

  implicit def showConsensusInput: Show[ConsensusInput] = {
    case OwnRoundTrigger   => "OwnRoundTrigger"
    case InspectionTrigger => "InspectionTrigger"
    case Proposal(roundId, senderId, _, _, dataUpdates, dataHashes) =>
      s"Proposal(roundId=${roundId.value.toString
          .take(8)}, senderId=${senderId.value.value.take(8)}, dataUpdatesCount=${dataUpdates.size}, dataHashesCount=${dataHashes.size})"
    case SignatureProposal(roundId, senderId, _, _) =>
      s"SignatureProposal(roundId=${roundId.value.toString.take(8)}, senderId=${senderId.value.value.take(8)})"
    case CancelledCreationRound(roundId, senderId, _, reason) =>
      s"CancelledCreationRound(roundId=${roundId.value.toString.take(8)}, senderId=${senderId.value.value.take(8)}, reason=$reason)"
  }
}
