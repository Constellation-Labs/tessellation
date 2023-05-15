package org.tessellation.dag.l1.domain.consensus.block

import cats.data.NonEmptySet
import cats.effect.Async
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput.{BlockSignatureProposal, CancelledBlockCreationRound, Proposal}
import org.tessellation.dag.l1.domain.consensus.round.RoundId
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.Block
import org.tessellation.schema.block.Tips
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.sdk.domain.transaction.TransactionValidator
import org.tessellation.sdk.domain.transaction.filter.Consecutive
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.SignatureProof
import org.tessellation.syntax.sortedCollection._

import monocle.macros.syntax.lens._
import org.typelevel.log4cats.slf4j.Slf4jLogger

case class RoundData(
  roundId: RoundId,
  startedAt: FiniteDuration,
  peers: Set[Peer],
  owner: PeerId,
  ownProposal: Proposal,
  ownBlock: Option[Signed[Block]] = None,
  ownCancellation: Option[CancellationReason] = None,
  peerProposals: Map[PeerId, Proposal] = Map.empty[PeerId, Proposal],
  peerBlockSignatures: Map[PeerId, SignatureProof] = Map.empty,
  peerCancellations: Map[PeerId, CancellationReason] = Map.empty,
  tips: Tips
) {

  private def logger[F[_]: Async] = Slf4jLogger.getLogger

  def addPeerProposal(proposal: Proposal): RoundData =
    this.focus(_.peerProposals).modify(_ + (proposal.senderId -> proposal))

  def setOwnBlock(block: Signed[Block]): RoundData = this.focus(_.ownBlock).replace(block.some)

  def addPeerBlockSignature(blockSignatureProposal: BlockSignatureProposal): RoundData = {
    val proof = SignatureProof(PeerId._Id.get(blockSignatureProposal.senderId), blockSignatureProposal.signature)
    this.focus(_.peerBlockSignatures).modify(_ + (blockSignatureProposal.senderId -> proof))
  }

  def setOwnCancellation(reason: CancellationReason): RoundData = this.focus(_.ownCancellation).replace(reason.some)

  def addPeerCancellation(cancellation: CancelledBlockCreationRound): RoundData =
    this.focus(_.peerCancellations).modify(_ + (cancellation.senderId -> cancellation.reason))

  def formBlock[F[_]: Async: KryoSerializer](validator: TransactionValidator[F]): F[Option[Block]] =
    (ownProposal.transactions ++ peerProposals.values.flatMap(_.transactions)).toList
      .traverse(validator.validate)
      .flatMap { validatedTxs =>
        val (invalid, valid) = validatedTxs.partitionMap(_.toEither)

        invalid.traverse { errors =>
          logger.warn(s"Discarded invalid transaction during L1 consensus with roundId=$roundId. Reasons: ${errors.show}")
        } >>
          valid.pure[F]
      }
      .flatMap {
        _.groupBy(_.source).values.toList
          .traverse(txs => Consecutive.take(txs))
          .map(listOfTxs => NonEmptySet.fromSet(listOfTxs.flatten.toSortedSet))
          .map(_.map(Block(tips.value, _)))
      }
}
