package org.tessellation.dag.l0.infrastructure.snapshot

import cats.effect.kernel.Sync

import org.tessellation.dag.l0.infrastructure.snapshot.schema.GlobalConsensusKind._
import org.tessellation.dag.l0.infrastructure.snapshot.schema._
import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.infrastructure.consensus.message.ConsensusWithdrawPeerDeclaration
import org.tessellation.schema.SnapshotOrdinal._

object GlobalSnapshotConsensusStateRemover {
  def make[F[_]: Sync](
    consensusStorage: GlobalConsensusStorage[F],
    gossip: Gossip[F]
  ): GlobalConsensusStateRemover[F] =
    new GlobalConsensusStateRemover[F](consensusStorage, gossip) {

      def getWithdrawalDeclaration(
        key: GlobalSnapshotKey,
        maybeState: Option[GlobalSnapshotConsensusState]
      ): ConsensusWithdrawPeerDeclaration[GlobalSnapshotKey, GlobalConsensusKind] = {
        val (declarationKey, declarationKind) = maybeState.map { state =>
          state.status match {
            case CollectingFacilities(_, _)       => (state.key, Proposal)
            case CollectingProposals(_, _, _, _)  => (state.key, Signature)
            case CollectingSignatures(_, _, _, _) => (state.key.next, Facility)
            case Finished(_, _, _, _, _)          => (state.key.next, Facility)
          }
        }.getOrElse((key, Facility))

        ConsensusWithdrawPeerDeclaration(declarationKey, declarationKind)
      }
    }
}
