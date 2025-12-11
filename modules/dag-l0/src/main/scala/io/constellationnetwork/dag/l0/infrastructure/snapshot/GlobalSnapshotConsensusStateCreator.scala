package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.effect.Async
import cats.effect.kernel.{Clock, Sync}
import cats.syntax.all._

import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.{CollectingFacilities, GlobalConsensusKind, GlobalConsensusOutcome}
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.Facility
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusPeerDeclaration
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.schema.peer.PeerId

import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

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
  def make[F[_]: Async](
    consensusFns: GlobalSnapshotConsensusFunctions[F],
    consensusStorage: GlobalConsensusStorage[F],
    gossip: Gossip[F],
    selfId: PeerId,
    seedlist: Option[Set[SeedlistEntry]]
  ): GlobalSnapshotConsensusStateCreator[F] = new GlobalSnapshotConsensusStateCreator[F] {

    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

    def tryFacilitateConsensus(
      key: GlobalSnapshotKey,
      lastOutcome: GlobalConsensusOutcome,
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind]
    ): F[StateCreateResult] =
      consensusStorage
        .condModifyState(key)(toCreateStateFn(facilitateConsensus(key, lastOutcome, maybeTrigger, resources)))
        .flatMap(evalEffect)
        .flatTap(logIfCreated)

    private def facilitateConsensus(
      key: GlobalSnapshotKey,
      lastOutcome: GlobalConsensusOutcome,
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind]
    ): F[(GlobalSnapshotConsensusState, F[Unit])] =
      for {
        candidates <- consensusStorage.getCandidates(key.next)
        previousFacilitators = lastOutcome.facilitators.value
        approvedCandidates = lastOutcome.finished.candidates.value
        seedlistPeerIds = seedlist.map(_.map(_.peerId)).getOrElse(Set.empty)

        filteredPreviousFacilitators = previousFacilitators
          .filter(peerId => seedlist.isEmpty || seedlistPeerIds.contains(peerId))

        filteredCandidates = approvedCandidates
          .filter(peerId => seedlist.isEmpty || seedlistPeerIds.contains(peerId))

        baseFacilitators = (filteredPreviousFacilitators ++ filteredCandidates).distinct

        _ <- logger.debug(
          s"Facilitator selection for key=$key: " +
            s"previous=${filteredPreviousFacilitators.size}, " +
            s"candidates=${filteredCandidates.size}, " +
            s"base=${baseFacilitators.size}"
        )

        facilitators <- (baseFacilitators :+ selfId).distinct
          .filterA(
            consensusFns.facilitatorFilter(
              lastOutcome.finished.signedMajorityArtifact,
              lastOutcome.finished.context,
              _
            )
          )
          .map(_.sorted)
          .map { list =>
            if (list.isEmpty) List(selfId) else list
          }

        filteredOut = baseFacilitators.filterNot(facilitators.contains)
        _ <- filteredOut.traverse_ { peerId =>
          logger.debug(s"Facilitator ${peerId.show} removed by facilitatorFilter for key=$key")
        }

        (withdrawn, active) = facilitators.partition { peerId =>
          resources.withdrawalsMap.get(peerId).contains(GlobalConsensusKind.Facility)
        }

        _ <- withdrawn.traverse_ { peerId =>
          logger.info(s"Facilitator ${peerId.show} has withdrawn from consensus at key=$key")
        }

        time <- Clock[F].monotonic

        effect = consensusStorage.getUpperBound.flatMap { bound =>
          gossip.spread(
            ConsensusPeerDeclaration(
              key,
              Facility(
                bound,
                candidates,
                maybeTrigger,
                lastOutcome.finished.facilitatorsHash,
                lastOutcome.key,
                lastOutcome.finished.snapshotHash
              )
            )
          )
        }

        state = ConsensusState[GlobalSnapshotKey, GlobalSnapshotStatus, GlobalConsensusOutcome, GlobalConsensusKind](
          key,
          lastOutcome,
          Facilitators(active),
          CollectingFacilities(
            maybeTrigger,
            lastOutcome.finished.facilitatorsHash,
            lastOutcome.finished.snapshotHash
          ),
          time,
          withdrawnFacilitators = WithdrawnFacilitators(withdrawn.toSet),
          spreadAckKinds = Set.empty
        )

        _ <- logger.info(
          s"Created consensus state for key=$key with ${active.size} active facilitators" +
            withdrawn.headOption.fold("")(_ => s", ${withdrawn.size} withdrawn")
        )

      } yield (state, effect)
  }
}
