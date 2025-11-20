package io.constellationnetwork.currency.l0.snapshot

import cats.Applicative
import cats.effect.kernel.Clock
import cats.effect.{Async, Sync}
import cats.syntax.all._

import io.constellationnetwork.currency.l0.snapshot.schema.{CollectingFacilities, CurrencyConsensusKind, CurrencyConsensusOutcome}
import io.constellationnetwork.currency.schema.currency.CurrencySnapshotContext
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.Facility
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusPeerDeclaration
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema.peer.{PeerId, Responsive, Unresponsive}
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}

import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

abstract class CurrencySnapshotConsensusStateCreator[F[_]: Sync]
    extends ConsensusStateCreator[
      F,
      CurrencySnapshotKey,
      CurrencySnapshotArtifact,
      CurrencySnapshotContext,
      CurrencySnapshotStatus,
      CurrencyConsensusOutcome,
      CurrencyConsensusKind
    ]

object CurrencySnapshotConsensusStateCreator {

  def make[F[_]: Async](
    consensusFns: CurrencySnapshotConsensusFunctions[F],
    consensusStorage: CurrencyConsensusStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    gossip: Gossip[F],
    selfId: PeerId,
    seedlist: Option[Set[SeedlistEntry]],
    clusterStorage: ClusterStorage[F]
  ): CurrencySnapshotConsensusStateCreator[F] = new CurrencySnapshotConsensusStateCreator[F] {
    case class FacilitatorWithStatus(peerId: PeerId, isHealthy: Boolean, message: Option[String])

    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

    def tryFacilitateConsensus(
      key: CurrencySnapshotKey,
      lastOutcome: CurrencyConsensusOutcome,
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
    ): F[StateCreateResult] =
      consensusStorage
        .condModifyState(key)(toCreateStateFn(facilitateConsensus(key, lastOutcome, maybeTrigger, resources)))
        .flatMap(evalEffect)
        .flatTap(logIfCreatedState)

    private def facilitateConsensus(
      key: CurrencySnapshotKey,
      lastOutcome: CurrencyConsensusOutcome,
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
    ): F[(CurrencySnapshotConsensusState, F[Unit])] = {
      val oldFacilitators = lastOutcome.facilitators.value
        .filter(peerId => seedlist.forall(_.map(_.peerId).contains(peerId)))

      val newCandidates = lastOutcome.finished.candidates.value
        .filter(peerId => seedlist.forall(_.map(_.peerId).contains(peerId)))

      for {
        oldFacilitatorsWithStatus <- oldFacilitators.traverse { peerId =>
          if (peerId === selfId) {
            Applicative[F].pure(
              FacilitatorWithStatus(peerId, isHealthy = true, "self".some)
            )
          } else {
            clusterStorage.getPeer(peerId).map {
              case Some(peer) if peer.responsiveness === Responsive =>
                FacilitatorWithStatus(peerId, isHealthy = true, "responsive".some)
              case Some(peer) if peer.responsiveness === Unresponsive =>
                FacilitatorWithStatus(peerId, isHealthy = false, "unresponsive".some)
              case Some(peer) =>
                FacilitatorWithStatus(peerId, isHealthy = false, peer.responsiveness.show.some)
              case None =>
                FacilitatorWithStatus(peerId, isHealthy = false, "not found in cluster storage".some)
            }
          }
        }

        removedFacilitators = oldFacilitatorsWithStatus.filterNot(_.isHealthy)
        _ <- removedFacilitators.traverse_(facilitatorWithStatus =>
          logger.warn(
            s"Removing old facilitator ${facilitatorWithStatus.peerId.show} from consensus - reason: ${facilitatorWithStatus.message.getOrElse("unknown")}"
          )
        )

        responsiveOldFacilitators = oldFacilitatorsWithStatus.filter(_.isHealthy).map(_.peerId)

        baseFacilitators = (responsiveOldFacilitators ++ newCandidates).distinct

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

        failedFilter = baseFacilitators.filterNot(facilitators.contains)
        _ <- failedFilter.traverse_ { peerId =>
          logger.warn(s"Facilitator ${peerId.show} removed by facilitatorFilter")
        }

        (withdrawn, remained) = facilitators.partition { peerId =>
          resources.withdrawalsMap.get(peerId).contains(CurrencyConsensusKind.Facility)
        }

        _ <- withdrawn.traverse_ { peerId =>
          logger.info(s"Facilitator ${peerId.show} has withdrawn from consensus")
        }

        candidatesForNextRound <- consensusStorage.getCandidates(key.next)
        healthyCandidates = candidatesForNextRound.value.filterNot(peerId => removedFacilitators.map(_.peerId).contains(peerId))
        finalCandidates = Candidates(remained.toSet ++ healthyCandidates)

        time <- Clock[F].monotonic
        lastGlobalSnapshotOrdinal <- lastGlobalSnapshotStorage.getOrdinal.map(_.getOrElse(SnapshotOrdinal.MinValue))

        upperBound <- consensusStorage.getUpperBound
        facilityDeclaration = Facility(
          upperBound,
          finalCandidates,
          maybeTrigger,
          lastOutcome.finished.facilitatorsHash,
          lastGlobalSnapshotOrdinal
        )

        effect = gossip.spread(
          ConsensusPeerDeclaration(key, facilityDeclaration)
        )

        state = ConsensusState[CurrencySnapshotKey, CurrencySnapshotStatus, CurrencyConsensusOutcome, CurrencyConsensusKind](
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
}
