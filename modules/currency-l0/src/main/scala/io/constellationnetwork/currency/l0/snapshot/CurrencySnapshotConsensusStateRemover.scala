package io.constellationnetwork.currency.l0.snapshot

import cats.effect.kernel.Sync

import io.constellationnetwork.currency.l0.snapshot.schema.CurrencyConsensusKind._
import io.constellationnetwork.currency.l0.snapshot.schema._
import io.constellationnetwork.currency.l0.snapshot.synchronous.message.ConsensusWithdrawPeerDeclaration
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.schema.SnapshotOrdinal._

object CurrencySnapshotConsensusStateRemover {

  private[snapshot] def withdrawalTarget(
    key: CurrencySnapshotKey,
    maybeCollectingKind: Option[CurrencyConsensusKind],
    hasActiveState: Boolean
  ): (CurrencySnapshotKey, CurrencyConsensusKind) = {
    val noStateTarget: (CurrencySnapshotKey, CurrencyConsensusKind) = (key, Facility)

    if (!hasActiveState) noStateTarget
    else
      maybeCollectingKind.fold[(CurrencySnapshotKey, CurrencyConsensusKind)](key.next -> Facility) {
        case Facility => (key, Proposal)
        case Proposal => (key, Signature)
        // Entering CollectingSignatures emits this node's artifact signature, but its
        // current-round binary signature has not yet been emitted. Target the current
        // BinarySignature phase so retained peers can remove this node without an
        // avoidable timeout (or an N=2 permanent wedge).
        case Signature       => (key, BinarySignature)
        case BinarySignature => (key.next, Facility)
      }
  }

  def make[F[_]: Sync](
    consensusStorage: CurrencyConsensusStorage[F],
    gossip: Gossip[F]
  ): CurrencyConsensusStateRemover[F] =
    new CurrencyConsensusStateRemover[F](consensusStorage, gossip) {

      def getWithdrawalDeclaration(
        key: CurrencySnapshotKey,
        maybeState: Option[CurrencySnapshotConsensusState]
      ): ConsensusWithdrawPeerDeclaration[CurrencySnapshotKey, CurrencyConsensusKind] = {
        val (declarationKey, declarationKind) =
          maybeState.fold(withdrawalTarget(key, Option.empty, hasActiveState = false))(state =>
            withdrawalTarget(
              state.key,
              CurrencySnapshotConsensusOps.make.maybeCollectingKind(state.status),
              hasActiveState = true
            )
          )

        ConsensusWithdrawPeerDeclaration(declarationKey, declarationKind)
      }
    }
}
