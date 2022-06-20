package org.tessellation.dag.l1.domain.consensus.block

import cats.data.NonEmptySet
import cats.syntax.option._

import scala.concurrent.duration.FiniteDuration

import org.tessellation.dag.domain.block.{DAGBlock, Tips}
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput.{
  BlockSignatureProposal,
  CancelledBlockCreationRound,
  Proposal
}
import org.tessellation.dag.l1.domain.consensus.round.RoundId
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.SignatureProof
import org.tessellation.syntax.sortedCollection._

import monocle.macros.syntax.lens._

case class RoundData(
  roundId: RoundId,
  startedAt: FiniteDuration,
  peers: Set[Peer],
  owner: PeerId,
  ownProposal: Proposal,
  ownBlock: Option[Signed[DAGBlock]] = None,
  ownCancellation: Option[CancellationReason] = None,
  peerProposals: Map[PeerId, Proposal] = Map.empty,
  peerBlockSignatures: Map[PeerId, SignatureProof] = Map.empty,
  peerCancellations: Map[PeerId, CancellationReason] = Map.empty,
  tips: Tips
) {

  def addPeerProposal(proposal: Proposal): RoundData =
    this.focus(_.peerProposals).modify(_ + (proposal.senderId -> proposal))

  def setOwnBlock(block: Signed[DAGBlock]): RoundData = this.focus(_.ownBlock).replace(block.some)

  def addPeerBlockSignature(blockSignatureProposal: BlockSignatureProposal): RoundData = {
    val proof = SignatureProof(PeerId._Id.get(blockSignatureProposal.senderId), blockSignatureProposal.signature)
    this.focus(_.peerBlockSignatures).modify(_ + (blockSignatureProposal.senderId -> proof))
  }

  def setOwnCancellation(reason: CancellationReason): RoundData = this.focus(_.ownCancellation).replace(reason.some)

  def addPeerCancellation(cancellation: CancelledBlockCreationRound): RoundData =
    this.focus(_.peerCancellations).modify(_ + (cancellation.senderId -> cancellation.reason))

  def formBlock(): Option[DAGBlock] =
    NonEmptySet
      .fromSet((ownProposal.transactions ++ peerProposals.values.flatMap(_.transactions).toSet).toSortedSet)
      .map(DAGBlock(tips.value, _))

}
