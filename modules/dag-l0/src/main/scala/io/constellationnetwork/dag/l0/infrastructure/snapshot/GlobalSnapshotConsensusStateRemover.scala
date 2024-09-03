package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.effect.kernel.Sync

import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.GlobalConsensusKind._
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema._
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusWithdrawPeerDeclaration
import io.constellationnetwork.schema.SnapshotOrdinal._

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
