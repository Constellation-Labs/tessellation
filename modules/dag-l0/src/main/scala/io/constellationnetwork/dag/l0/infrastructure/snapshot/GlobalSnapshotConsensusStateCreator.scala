package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.Applicative
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
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.schema.peer.{PeerId, Responsive, Unresponsive}
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
    seedlist: Option[Set[SeedlistEntry]],
    clusterStorage: ClusterStorage[F]
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
        .flatTap(logIfCreatedState)

    private def facilitateConsensus(
      key: GlobalSnapshotKey,
      lastOutcome: GlobalConsensusOutcome,
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind]
    ): F[(GlobalSnapshotConsensusState, F[Unit])] =
      for {

        candidates <- consensusStorage.getCandidates(key.next)

        // Split into old facilitators and new candidates
        oldFacilitators = lastOutcome.facilitators.value
          .filter(peerId => seedlist.forall(_.map(_.peerId).contains(peerId)))

        newCandidates = lastOutcome.finished.candidates.value
          .filter(peerId => seedlist.forall(_.map(_.peerId).contains(peerId)))

        // Check responsiveness for each old facilitator
        oldFacilitatorsWithStatus <- oldFacilitators.traverse { peerId =>
          if (peerId === selfId) {
            Applicative[F].pure((peerId, true, "self".some))
          } else {
            clusterStorage.getPeer(peerId).map {
              case Some(peer) if peer.responsiveness === Responsive =>
                (peerId, true, "responsive".some)
              case Some(peer) if peer.responsiveness === Unresponsive =>
                (peerId, false, "unresponsive".some)
              case Some(peer) =>
                (peerId, false, peer.responsiveness.show.some)
              case None =>
                (peerId, false, "not found in cluster storage".some)
            }
          }
        }

        removedFacilitators = oldFacilitatorsWithStatus.filterNot(_._2)
        _ <- removedFacilitators.traverse_ { case (peerId, _, reason) =>
          logger.warn(s"Removing old facilitator ${peerId.show} from consensus - reason: ${reason.getOrElse("unknown")}")
        }

        responsiveOldFacilitators = oldFacilitatorsWithStatus.filter(_._2).map(_._1)

        baseFacilitators = (responsiveOldFacilitators ++ newCandidates).distinct

        facilitators <- baseFacilitators
          .filterA(consensusFns.facilitatorFilter(
            lastOutcome.finished.signedMajorityArtifact,
            lastOutcome.finished.context,
            _
          ))
          .map(_.prepended(selfId).distinct.sorted)
          .map { list =>
            if (list.isEmpty) List(selfId)
            else list
          }

        failedFilter = baseFacilitators.filterNot(facilitators.contains)
        _ <- failedFilter.traverse_ { peerId =>
          logger.warn(s"Facilitator ${peerId.show} removed by facilitatorFilter")
        }

        (withdrawn, remained) = facilitators.partition { peerId =>
          resources.withdrawalsMap.get(peerId).contains(GlobalConsensusKind.Facility)
        }

        _ <- withdrawn.traverse_ { peerId =>
          logger.info(s"Facilitator ${peerId.show} has withdrawn from consensus")
        }

        time <- Clock[F].monotonic
        effect = consensusStorage.getUpperBound.flatMap { bound =>
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
