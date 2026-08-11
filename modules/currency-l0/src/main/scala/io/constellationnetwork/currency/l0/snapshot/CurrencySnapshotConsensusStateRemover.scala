package io.constellationnetwork.currency.l0.snapshot

import cats.effect.kernel.Sync

import io.constellationnetwork.currency.l0.snapshot.schema.CurrencyConsensusKind._
import io.constellationnetwork.currency.l0.snapshot.schema._
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusWithdrawPeerDeclaration
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.schema.SnapshotOrdinal._

object CurrencySnapshotConsensusStateRemover {

  def make[F[_]: Sync](
    consensusStorage: CurrencyConsensusStorage[F],
    gossip: Gossip[F]
  ): CurrencyConsensusStateRemover[F] =
    new CurrencyConsensusStateRemover[F](consensusStorage, gossip) {

      protected def getWithdrawalDeclaration(
        key: CurrencySnapshotKey,
        maybeState: Option[CurrencySnapshotConsensusState]
      ): ConsensusWithdrawPeerDeclaration[CurrencySnapshotKey, CurrencyConsensusKind] = {
        val (declarationKey, declarationKind) = maybeState.map { state =>
          state.status match {
            case _: CollectingFacilities       => (state.key, Proposal)
            case _: CollectingProposals        => (state.key, Signature)
            case _: CollectingSignatures       => (state.key.next, BinarySignature)
            case _: CollectingBinarySignatures => (state.key.next, Facility)
            case _: Finished                   => (state.key.next, Facility)
          }
        }.getOrElse((key, Facility))

        ConsensusWithdrawPeerDeclaration(declarationKey, declarationKind)
      }
    }
}
