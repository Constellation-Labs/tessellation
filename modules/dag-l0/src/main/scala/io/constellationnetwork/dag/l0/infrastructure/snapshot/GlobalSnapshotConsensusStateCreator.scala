package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.effect.Async
import cats.effect.kernel.{Clock, Sync}
import cats.syntax.all._

import io.constellationnetwork.dag.l0.infrastructure.metrics.ConsensusMetrics
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.{CollectingFacilities, GlobalConsensusKind, GlobalConsensusOutcome}
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.seedlist.SeedlistEntry
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.Facility
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusPeerDeclaration
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.peer.PeerId

import eu.timepit.refined.auto._

abstract class GlobalSnapshotConsensusStateCreator[F[_]: Sync]
    extends ConsensusStateCreator[
      F,
      GlobalSnapshotKey,
      GlobalSnapshotArtifact,
      GlobalSnapshotContext,
      GlobalSnapshotStatus,
      GlobalConsensusOutcome,
      GlobalConsensusKind
    ]

object GlobalSnapshotConsensusStateCreator {
  def make[F[_]: Async: Metrics](
    consensusFns: GlobalSnapshotConsensusFunctions[F],
    consensusStorage: GlobalConsensusStorage[F],
    gossip: Gossip[F],
    selfId: PeerId,
    seedlist: Option[Set[SeedlistEntry]]
  ): GlobalSnapshotConsensusStateCreator[F] = new GlobalSnapshotConsensusStateCreator[F] {
    def tryFacilitateConsensus(
      key: GlobalSnapshotKey,
      lastOutcome: GlobalConsensusOutcome,
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind]
    ): F[StateCreateResult] =
      consensusStorage
        .condModifyState(key)(toCreateStateFn(facilitateConsensus(key, lastOutcome, maybeTrigger, resources)))
        .flatMap(evalEffect)
        .flatTap(logIfCreatedState)

    private def facilitateConsensus(
      key: GlobalSnapshotKey,
      lastOutcome: GlobalConsensusOutcome,
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind]
    ): F[(GlobalSnapshotConsensusState, F[Unit])] =
      for {

        candidates <- consensusStorage.getCandidates(key.next)
        _ <- Metrics[F].recordDistribution("dag_gl0_consensus_creator_candidates", candidates.value.size)

        facilitators <- lastOutcome.facilitators.value
          .concat(lastOutcome.finished.candidates.value)
          .filter(peerId => seedlist.forall(_.map(_.peerId).contains(peerId)))
          .filterA(consensusFns.facilitatorFilter(lastOutcome.finished.signedMajorityArtifact, lastOutcome.finished.context, _))
          .map(_.prepended(selfId).distinct.sorted)
        _ <- Metrics[F].recordDistribution("dag_gl0_consensus_creator_facilitators", facilitators.size)

        (withdrawn, remained) = facilitators.partition { peerId =>
          resources.withdrawalsMap.get(peerId).contains(GlobalConsensusKind.Facility)
        }
        _ <- Metrics[F].recordDistribution("dag_gl0_consensus_creator_facilitators_withdrawn", withdrawn.size)
        _ <- Metrics[F].recordDistribution("dag_gl0_consensus_creator_facilitators_remained", remained.size)

        time <- Clock[F].monotonic
        effect = consensusStorage.getUpperBound.flatMap { bound =>
          Metrics[F].recordDistribution("dag_gl0_consensus_creator_bound_size", bound.size) >>
            Metrics[F].incrementCounter("dag_gl0_consensus_creator_gossip_spread") >>
            gossip.spread(
              ConsensusPeerDeclaration(
                key,
                Facility(bound, candidates, maybeTrigger, lastOutcome.finished.facilitatorsHash, lastOutcome.key)
              )
            )
        }
        state = ConsensusState[GlobalSnapshotKey, GlobalSnapshotStatus, GlobalConsensusOutcome, GlobalConsensusKind](
          key,
          lastOutcome,
          Facilitators(remained),
          CollectingFacilities(
            maybeTrigger,
            lastOutcome.finished.facilitatorsHash
          ),
          time,
          withdrawnFacilitators = WithdrawnFacilitators(withdrawn.toSet),
          spreadAckKinds = Set.empty
        )
      } yield (state, effect)
  }
}
