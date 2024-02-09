package org.tessellation.currency.l0.snapshot

import cats.effect.kernel.Sync

import org.tessellation.currency.l0.snapshot.schema.CurrencyConsensusKind._
import org.tessellation.currency.l0.snapshot.schema._
import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.infrastructure.consensus.message.ConsensusWithdrawPeerDeclaration
import org.tessellation.schema.SnapshotOrdinal._

object CurrencySnapshotConsensusStateRemover {
  def make[F[_]: Sync](
    consensusStorage: CurrencyConsensusStorage[F],
    gossip: Gossip[F]
  ): CurrencyConsensusStateRemover[F] =
    new CurrencyConsensusStateRemover[F](consensusStorage, gossip) {

      def getWithdrawalDeclaration(
        key: CurrencySnapshotKey,
        maybeState: Option[CurrencySnapshotConsensusState]
      ): ConsensusWithdrawPeerDeclaration[CurrencySnapshotKey, CurrencyConsensusKind] = {
        val (declarationKey, declarationKind) = maybeState.map { state =>
          state.status match {
            case CollectingFacilities(_, _)                   => (state.key, Proposal)
            case CollectingProposals(_, _, _, _)              => (state.key, Signature)
            case CollectingSignatures(_, _, _, _)             => (state.key.next, BinarySignature)
            case CollectingBinarySignatures(_, _, _, _, _, _) => (state.key.next, Facility)
            case Finished(_, _, _, _, _, _)                   => (state.key.next, Facility)
          }
        }.getOrElse((key, Facility))

        ConsensusWithdrawPeerDeclaration(declarationKey, declarationKind)
      }
    }
}
