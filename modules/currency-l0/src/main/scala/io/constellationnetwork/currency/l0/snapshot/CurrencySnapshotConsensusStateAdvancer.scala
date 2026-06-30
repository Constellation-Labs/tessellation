package io.constellationnetwork.currency.l0.snapshot

import java.security.KeyPair

import cats.Applicative
import cats.data.{NonEmptySet, StateT}
import cats.effect.{Async, Ref}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration.{FiniteDuration, _}

import io.constellationnetwork.currency.dataApplication.BaseDataApplicationL0Service
import io.constellationnetwork.currency.l0.snapshot.schema._
import io.constellationnetwork.currency.l0.snapshot.services.StateChannelSnapshotService
import io.constellationnetwork.currency.schema.CurrencyStateKey
import io.constellationnetwork.currency.schema.currency.CurrencySnapshotContext
import io.constellationnetwork.ext.collection.FoldableOps.pickMajority
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions.InvalidArtifact
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event}
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.consensus.message._
import io.constellationnetwork.node.shared.infrastructure.consensus.state.ConsensusStateUpdater._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.gossip.event.{EventGossipClient, IWantRequest}
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.selfhealth.SelfHealthHint
import io.constellationnetwork.node.shared.infrastructure.snapshot.{
  CurrencyArtifactMismatch,
  SnapshotDifferentThanExpected,
  SomeBlocksWereNotAccepted
}
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.currencyMessage.fetchStakingAddress
import io.constellationnetwork.schema.peer.{Peer, PeerId}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature._
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary
import io.constellationnetwork.syntax.sortedCollection.sortedSetSyntax

import eu.timepit.refined.auto._
import io.circe.{Decoder, Encoder}
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Advances Currency L0 (Metagraph) consensus through status phases and extracts final outcomes.
  *
  * Status Flow (note: has extra BinarySignatures phase compared to Global L0):
  * {{{
  *   CollectingFacilities → CollectingProposals → CollectingSignatures
  *     → CollectingBinarySignatures → Finished
  * }}}
  *
  * @see
  *   ConsensusStateAdvancer for the generic interface
  */
abstract class CurrencySnapshotConsensusStateAdvancer[F[_]]
    extends ConsensusStateAdvancer[
      F,
      CurrencySnapshotKey,
      CurrencySnapshotArtifact,
      CurrencySnapshotContext,
      CurrencySnapshotStatus,
      CurrencyConsensusOutcome,
      CurrencyConsensusKind
    ]

object CurrencySnapshotConsensusStateAdvancer {

  def make[F[_]: Async: SecurityProvider: Metrics: HasherSelector](
    consensusConfig: ConsensusConfig,
    keyPair: KeyPair,
    consensusStorage: CurrencyConsensusStorage[F],
    consensusFns: CurrencySnapshotConsensusFunctions[F],
    stateChannelSnapshotService: StateChannelSnapshotService[F],
    gossip: Gossip[F],
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]],
    restartService: RestartService[F, _],
    nodeStorage: NodeStorage[F],
    leavingDelay: FiniteDuration,
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    clusterStorageInstance: ClusterStorage[F],
    eventMempool: EventMempool[F, CurrencySnapshotEvent, CurrencyStateKey],
    eventGossipClient: EventGossipClient[F, CurrencySnapshotEvent],
    facilitatorSelector: FacilitatorSelector
  )(
    implicit eventEncoder: Encoder[CurrencySnapshotEvent],
    eventDecoder: Decoder[CurrencySnapshotEvent]
  ): CurrencySnapshotConsensusStateAdvancer[F] =
    new CurrencySnapshotConsensusStateAdvancer[F] {

      private val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass[F](getClass)

      protected val clusterStorage: ClusterStorage[F] = clusterStorageInstance
      protected val config: ConsensusConfig = consensusConfig

      /** Tracks the most recent divergent majority hash observed against this node's own hash, keyed by observation type. Read by
        * `recoverIfForking` to enforce a `forkConfirmationWindow` persistence requirement before flipping to `WaitingForDownload`. See
        * `recoverIfForking` docstring for the full state machine.
        */
      private val forkObservationsRef: Ref[F, Map[ConsensusStateUpdater.ForkObservation, (Hash, FiniteDuration)]] =
        Ref.unsafe(Map.empty)

      private case class Transition(newState: CurrencySnapshotConsensusState, sideEffect: F[Unit])

      override def isBootstrapActive(lastOutcome: CurrencyConsensusOutcome): Boolean =
        !lastOutcome.recentProofSizes.values.exists(_ >= config.bootstrapCompleteProofsThreshold)

      // v33 quorum-denominator shrink anchors (see QuorumDenominatorShrink). Both are
      // consensus-agreed outcome fields: the latest controllerEvidence entry's canonical
      // completedSigners and the parent round's facility-median consensusEndTime.
      override protected def latestEvidenceSigners(lastOutcome: CurrencyConsensusOutcome): Option[SortedSet[PeerId]] =
        lastOutcome.controllerEvidence.flatMap(_.lastOption).map { case (_, entry) => entry.completedSigners }

      override protected def lastOutcomeEndTimeMs(lastOutcome: CurrencyConsensusOutcome): Option[Long] =
        lastOutcome.recentRoundEndTimes.lastOption.map { case (_, endTime) => endTime }

      // v4.1.0 cluster-majority floor: enable the committee-supermajority finality floor outside bootstrap
      // (see QuorumDenominatorShrink.decide / ConsensusStateAdvancer.clusterFloorActive). isInBootstrap is
      // derived from consensus-agreed recentProofSizes, so this is deterministic across nodes.
      override protected def clusterFloorActive(state: CurrencySnapshotConsensusState): Boolean =
        !isInBootstrap(state)

      def getConsensusOutcome(
        state: CurrencySnapshotConsensusState
      ): Option[(Previous[CurrencySnapshotKey], CurrencyConsensusOutcome)] =
        state.status match {
          case f: Finished =>
            // Phase 3: derive penalty/quality state from CONSENSUS-AGREED inputs only.
            // See GlobalSnapshotConsensusStateAdvancer for the full rationale — summary:
            // `f.signedMajorityArtifact.proofs` varies across nodes for the same artifact
            // (maybeGetAllDeclarations stops at quorum; SnapshotStorage.prepend doesn't
            // merge later-arriving proofs; ForkInfo gossip carries only (ordinal, hash)).
            // Derive penalties, quality, and bootstrap classification from
            // `state.facilitators` / `state.removedFacilitators` only.
            val evictedPeers = state.removedFacilitators.value
            val previousPenalties = state.lastOutcome.removalPenalties
            val previousCumulative = state.lastOutcome.cumulativeMissCounts

            // v19 cleanup: deferralCountdown is inert; no deferral cohort to exclude. Mirror of dag-l0.
            val deferredInCommittee = Set.empty[PeerId]

            // Canonical committee (not mutable state.facilitators) — see
            // GlobalSnapshotConsensusStateAdvancer for the ord-5 fork rationale.
            val completedFacilitators = state.roundStartFacilitators.value.toSet -- evictedPeers
            val decayedCumulative = completedFacilitators.foldLeft(previousCumulative) { (acc, pid) =>
              acc.get(pid) match {
                case Some(v) if v > 1L => acc.updated(pid, v - 1L)
                case Some(_)           => acc - pid // reached 0 — prune so the map stays bounded
                case None              => acc // no prior miss history, nothing to decay
              }
            }

            // Bootstrap warmup: classify from consensus-agreed committee size rather than
            // locally-observed proofs count.
            val isInBootstrap =
              !state.lastOutcome.recentProofSizes.values.exists(_ >= config.bootstrapCompleteProofsThreshold)

            val penalizedThisRound =
              if (isInBootstrap) Set.empty[PeerId] else (evictedPeers -- deferredInCommittee).toSet
            val newCumulative = penalizedThisRound.foldLeft(decayedCumulative) { (acc, pid) =>
              acc.updated(pid, acc.getOrElse(pid, 0L) + 1L)
            }

            val decrementedPenalties = previousPenalties.view.mapValues(_ - 1).filter(_._2 > 0).to(SortedMap)
            val newPenalties = penalizedThisRound.foldLeft(decrementedPenalties) { (acc, pid) =>
              val repeatCount = newCumulative.getOrElse(pid, 1L) - 1L
              val base = config.exponentialPenaltyBase.toDouble
              val scaled = config.removalPenaltyRounds.toDouble * math.pow(base, repeatCount.toDouble)
              val penalty = math.min(scaled, config.maxRemovalPenaltyRounds.toDouble).toInt
              acc.updated(pid, math.max(1, penalty))
            }
            // v19 cleanup: deferralCountdown is inert; justUnpenalized seeds readmissionCountdown.
            // Mirror of dag-l0.
            val justUnpenalized = previousPenalties.filter(_._2 == 1).keySet

            // Grace window: peers with active deferralCountdown don't accrue participated
            // or completed. Symmetric suppression prevents the "freshly-Ready peer misses
            // first round, gets penalized, sits out, re-enters still behind" cascade.
            // See GlobalSnapshotConsensusStateAdvancer for full rationale.
            //
            // v7 (flaky-byzantine): see dag-l0 mirror — peerQuality "completed" reflects
            // actual facility-phase participation via state.observedResponders (replaced
            // at proposal-acceptance time). Bootstrap fallback to "non-evicted" semantic.
            val responderSet: Set[PeerId] =
              if (isInBootstrap) completedFacilitators
              else state.observedResponders.value
            val thisRoundQuality: SortedMap[PeerId, (Int, Int)] = SortedMap.from(
              // Canonical committee iteration — see dag-l0 mirror.
              state.roundStartFacilitators.value
                .filterNot(deferredInCommittee.contains)
                .map { pid =>
                  val completed = if (responderSet.contains(pid)) 1 else 0
                  pid -> (completed, 1)
                }
            )
            // Accumulate with previous rounds, apply deterministic decay and pruning
            val rawAccumulated: SortedMap[PeerId, (Int, Int)] = {
              val previous = state.lastOutcome.peerQuality
              val allPeerIds = (previous.keySet.toList ::: thisRoundQuality.keySet.toList).distinct
              SortedMap.from(allPeerIds.map { pid =>
                val (pc, pp) = previous.getOrElse(pid, (0, 0))
                val (tc, tp) = thisRoundQuality.getOrElse(pid, (0, 0))
                pid -> (pc + tc, pp + tp)
              })
            }
            val needsDecay = rawAccumulated.values.exists { case (_, p) => p > consensusConfig.qualityDecayThreshold }
            val decayed =
              if (needsDecay) rawAccumulated.view.mapValues { case (c, p) => (c / 2, p / 2) }.to(SortedMap)
              else rawAccumulated
            val accumulatedQuality = decayed.filter { case (_, (c, p)) => c > 0 || p > 0 }

            // Canonical (node-independent) committee and completed-signer set for the
            // just-finalized round, mirror of dag-l0. These feed the SIGNED-bytes windows
            // (recentProofSizes / recentSigners / controllerEvidence). `completedFacilitators`
            // above is NOT an allowed source: it subtracts `state.removedFacilitators`, whose
            // facility-phase fork-eviction component is computed from the LOCAL declaration
            // snapshot at quorum-crossing and diverges across honest nodes (the
            // ordinal-3150166 controllerEvidenceDiffer wedge class). Full determinism
            // argument: ControllerEvidenceDerivation.canonicalCompletedSigners.
            val canonicalCommitteeForRound: SortedSet[PeerId] =
              ControllerEvidenceDerivation.canonicalCommittee(
                roundStartFacilitators = SortedSet.from(state.roundStartFacilitators.value),
                certifiedEvictions = state.certifiedEvictionTargets
              )
            val canonicalSigners: SortedSet[PeerId] =
              ControllerEvidenceDerivation.canonicalCompletedSigners(
                roundStartFacilitators = SortedSet.from(state.roundStartFacilitators.value),
                acceptedObservedResponders = state.observedResponders.value,
                certifiedEvictions = state.certifiedEvictionTargets
              )

            // Roll the proofs-size window forward using the canonical committee size for
            // the completed round. Mirror of dag-l0 (committee-size semantics kept so the
            // bootstrap classification keyed on bootstrapCompleteProofsThreshold still
            // measures committee size).
            val bootstrapLookbackOrdinals = 10L
            val currentOrdValue = state.key.value.value
            val minOrdinalValue = math.max(0L, currentOrdValue - bootstrapLookbackOrdinals)
            val currentProofsSize: Int = canonicalCommitteeForRound.size
            val newRecentProofSizes: SortedMap[SnapshotOrdinal, Int] = {
              val withCurrent =
                state.lastOutcome.recentProofSizes.updated(state.key, currentProofsSize)
              withCurrent.filter { case (ord, _) => ord.value.value >= minOrdinalValue }
            }

            // v22: recentSigners repopulated as the rolling K-round CANONICAL signer-set window;
            // drives the tier-demotion hysteresis. Fully sorted -> deterministic. Mirror of dag-l0.
            val tighteningMinOrdinalValue =
              math.max(0L, currentOrdValue - config.tighteningWindow.toLong + 1L)
            val newRecentSigners: SortedMap[SnapshotOrdinal, SortedSet[PeerId]] = {
              val withCurrent =
                state.lastOutcome.recentSigners.updated(state.key, canonicalSigners)
              withCurrent.filter { case (ord, _) => ord.value.value >= tighteningMinOrdinalValue }
            }

            // v19/v22 multi-committee tier transitions mirror of dag-l0: demote a Core peer only on
            // sustained silence (absent from the most-recent DemotionConsecutiveMisses signer sets),
            // not a single miss -- the hysteresis that makes the lowered Core floor safe.
            val newPeerTiers: SortedMap[PeerId, Int] = TierTransitions.computeNextTiers(
              priorTiers = state.lastOutcome.peerTiers,
              roundStartFacilitators = state.roundStartFacilitators.value.toSet,
              recentSignersWindow = newRecentSigners,
              roundCompleted = true
            )

            // v19 phase 2 view-from-time anchor window, mirror of dag-l0.
            val newRecentRoundEndTimes: SortedMap[SnapshotOrdinal, Long] =
              state.outcomeEndTime match {
                case Some(endTime) =>
                  val withCurrent = state.lastOutcome.recentRoundEndTimes.updated(state.key, endTime)
                  withCurrent.filter { case (ord, _) => ord.value.value >= tighteningMinOrdinalValue }
                case None =>
                  state.lastOutcome.recentRoundEndTimes.filter { case (ord, _) => ord.value.value >= tighteningMinOrdinalValue }
              }
            val newActiveAdmissionScores: SortedMap[PeerId, Int] =
              ConsensusPeerController.advanceScores(
                prior = state.lastOutcome.activeAdmissionScores,
                evidence = ConsensusPeerController.RoundEvidence(
                  roundStart = state.roundStartFacilitators.value.toSet,
                  completed = completedFacilitators,
                  responders = responderSet,
                  timeoutVoters = state.acceptedTimeoutCertificateVoters.toSet,
                  evicted = evictedPeers,
                  observedSelfHealth = state.observedSelfHealth.value
                ),
                config = ConsensusPeerController.Config(
                  promoteThreshold = config.activeAdmissionPromoteThreshold,
                  retainThreshold = config.activeAdmissionRetainThreshold,
                  demoteThreshold = config.activeAdmissionDemoteThreshold,
                  maxScore = config.activeAdmissionMaxScore,
                  signatureReward = config.activeAdmissionSignatureReward,
                  responderReward = config.activeAdmissionResponderReward,
                  missedActivePenalty = config.activeAdmissionMissedActivePenalty,
                  timeoutMissingPenalty = config.activeAdmissionTimeoutMissingPenalty,
                  evictedPenalty = config.activeAdmissionEvictedPenalty,
                  degradedPenalty = config.activeAdmissionDegradedPenalty,
                  criticalPenalty = config.activeAdmissionCriticalPenalty,
                  passiveDecay = config.activeAdmissionPassiveDecay,
                  maxExpansionPerRound = config.activeAdmissionMaxExpansionPerRound
                )
              )

            // Controller evidence stage 1, mirror of dag-l0: append the just-finalized round's
            // canonical facts to the bounded evidence window. All inputs consensus-agreed
            // (completedSigners is the proposal-carried canonical set shared with the
            // recentSigners window above); see GlobalSnapshotConsensusStateAdvancer and
            // ControllerEvidenceDerivation.canonicalCompletedSigners for the full rationale.
            val controllerEvidenceEntry = ControllerEvidenceEntry(
              roundStartFacilitators = SortedSet.from(state.roundStartFacilitators.value),
              completedSigners = canonicalSigners,
              timeoutVoters = state.acceptedTimeoutCertificateVoters,
              admittedPeers = SortedSet.from(state.admittedFacilitators.value),
              evictedPeers = state.certifiedEvictionTargets
            )
            val newControllerEvidence: SortedMap[SnapshotOrdinal, ControllerEvidenceEntry] =
              ControllerEvidenceDerivation.appendBounded(
                prior = state.lastOutcome.controllerEvidence.getOrElse(SortedMap.empty),
                key = state.key,
                entry = controllerEvidenceEntry,
                tighteningWindow = config.tighteningWindow
              )
            // Controller evidence stage 3, mirror of dag-l0: cert-anchored penalty horizons.
            // Write-only for now.
            val newPenaltyUntil: SortedMap[PeerId, SnapshotOrdinal] =
              ControllerEvidenceDerivation.nextPenaltyUntil(
                prior = state.lastOutcome.penaltyUntil.getOrElse(SortedMap.empty),
                certifiedEvictions = state.certifiedEvictionTargets,
                certifiedAdmissions = state.admittedFacilitators.value,
                currentOrdinal = state.key,
                penaltyDurationOrdinals = config.penaltyDurationOrdinals
              )

            // B2 readmissionCountdown maintenance (v12 sticky-probation, see dag-l0 mirror for
            // full rationale). decrement (clamped at 0) → seed justUnpenalized → clear admitted.
            // Pre-v12 auto-cleared the entry when countdown hit 0; v12 keeps the key so only
            // an AdmissionCertificate (via the `-- admittedThisRound` step below) can clear it.
            val admittedThisRound = state.admittedFacilitators.value
            val finalReadmission = ReadmissionMaintenance.step(
              prev = state.lastOutcome.readmissionCountdown,
              justUnpenalized = justUnpenalized,
              admittedThisRound = admittedThisRound,
              probationRounds = config.readmissionProbationRounds
            )
            // Per-peer cumulative view-change-caused credits.
            // Mirror of dag-l0; see GlobalSnapshotConsensusStateAdvancer for full rationale and
            // the determinism contract. v19: priorActive draws from `state.coreFacilitators`
            // (Core committee = leader pool), not the full round-start committee.
            val priorPeerQuality = state.lastOutcome.peerQuality
            val priorActive = state.coreFacilitators.value
            val priorGraduated = priorActive.filter { pid =>
              val (completed, participated) = priorPeerQuality.getOrElse(pid, (0, 0))
              participated >= config.minParticipationObservations && completed >= 1
            }
            val priorLeaderPool = if (priorGraduated.size >= 2) priorGraduated else priorActive
            val viewChangeCredits: SortedMap[PeerId, Long] =
              if (state.viewNumber <= 0 || priorLeaderPool.isEmpty) SortedMap.empty[PeerId, Long]
              else {
                val priorPeerQualityMap: Map[PeerId, (Int, Int)] = priorPeerQuality.toMap
                val priorPeerSelfHealthMap = state.lastOutcome.peerSelfHealth.toMap
                val priorPeerViewChangesMap = state.lastOutcome.peerViewChanges.toMap
                (0 until state.viewNumber).foldLeft(SortedMap.empty[PeerId, Long]) { (acc, v) =>
                  val failedLeader = facilitatorSelector.selectLeaderWeighted(
                    priorLeaderPool,
                    state.entropy,
                    viewNumber = v,
                    qualityScores = priorPeerQualityMap,
                    selfHealthHints = priorPeerSelfHealthMap,
                    peerViewChanges = priorPeerViewChangesMap,
                    minLeaderRatioPct = config.leaderRotationMinRatioPct,
                    hardLeaderQualityScorePct = config.hardLeaderQualityScorePct,
                    minLeaderPoolSize = config.minLeaderPoolSize
                  )
                  acc.updated(failedLeader, acc.getOrElse(failedLeader, 0L) + 1L)
                }
              }
            val accumulatedPeerViewChanges: SortedMap[PeerId, Long] = {
              val priorMap = state.lastOutcome.peerViewChanges
              val allKeys = (priorMap.keysIterator ++ viewChangeCredits.keysIterator).toSet
              SortedMap
                .from(allKeys.iterator.map { pid =>
                  pid -> (priorMap.getOrElse(pid, 0L) + viewChangeCredits.getOrElse(pid, 0L))
                })
                .filter { case (_, v) => v > 0L }
            }
            val nextOutcomeFacilitators = Facilitators(
              ConsensusPeerController.applyCertifiedAdmissions(
                state.roundStartFacilitators.value,
                state.admittedFacilitators.value
              )
            )
            val outcome = CurrencyConsensusOutcome(
              state.key,
              // Canonical committee persists in lastOutcome — see dag-l0 mirror.
              nextOutcomeFacilitators,
              state.removedFacilitators,
              state.withdrawnFacilitators,
              state.eligibleFacilitators,
              f,
              removalPenalties = if (config.removalPenaltyRounds > 0) newPenalties else SortedMap.empty,
              // v19 cleanup: inert -- no StateCreator consumer. Mirror of dag-l0.
              deferralCountdown = SortedMap.empty[PeerId, Int],
              peerQuality = accumulatedQuality,
              cumulativeMissCounts = newCumulative,
              recentProofSizes = newRecentProofSizes,
              readmissionCountdown = finalReadmission,
              // v15: carry the accepted Proposal's `observedSelfHealth` forward, mirror of dag-l0.
              peerSelfHealth = state.observedSelfHealth.value,
              // v16: per-peer cumulative view-change-caused, mirror of dag-l0.
              peerViewChanges = accumulatedPeerViewChanges,
              // v22: rolling K-round signer-set window, repopulated to drive the tier-demotion
              // hysteresis and carried forward as the next round's window. Mirror of dag-l0.
              recentSigners = newRecentSigners,
              // v19 multi-committee tier classification carried forward, mirror of dag-l0.
              peerTiers = newPeerTiers,
              activeAdmissionScores = newActiveAdmissionScores,
              lastTimeoutCertificateVoters = state.acceptedTimeoutCertificateVoters,
              // v19 phase 2 view-from-time anchor window, mirror of dag-l0.
              recentRoundEndTimes = newRecentRoundEndTimes,
              // Controller evidence stages 1+3 (write-only), mirror of dag-l0.
              controllerEvidence = if (newControllerEvidence.nonEmpty) Some(newControllerEvidence) else None,
              penaltyUntil = if (newPenaltyUntil.nonEmpty) Some(newPenaltyUntil) else None
            )
            (Previous(state.lastOutcome.key), outcome).some
          case _ =>
            none
        }

      def advanceStatus(
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      ): StateT[F, CurrencySnapshotConsensusState, F[Unit]] =
        StateT { state =>
          HasherSelector[F].withCurrent { implicit hasher =>
            tryAdvance(state, resources).map {
              case Some(t) => (t.newState, t.sideEffect)
              case None    => (state, Applicative[F].unit)
            }
          }
        }

      private def tryAdvance(
        state: CurrencySnapshotConsensusState,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      )(implicit hasher: Hasher[F]): F[Option[Transition]] =
        state.status match {
          case s: CollectingFacilities       => advanceFromFacilities(state, s, resources)
          case s: CollectingProposals        => advanceFromProposals(state, s, resources)
          case s: CollectingSignatures       => advanceFromSignatures(state, s, resources)
          case s: CollectingBinarySignatures => advanceFromBinarySignatures(state, s, resources)
          case _: Finished                   => none[Transition].pure[F]
        }

      // =========================================================================
      // COLLECTING FACILITIES → COLLECTING PROPOSALS
      // =========================================================================

      private def advanceFromFacilities(
        state: CurrencySnapshotConsensusState,
        status: CollectingFacilities,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      ): F[Option[Transition]] =
        for {
          maybeFacilities <- maybeGetAllDeclarations(state, resources)(_.facility)
          // NOTE: facilitatorsHash fork check is handled by identifyForkedPeers below (evicts minority
          // instead of killing this node). Do NOT call checkForkByFacilitatorsHash here — after stall-based
          // eviction, different nodes may legitimately have different facilitator sets, which would cause
          // cascading false-positive fork detections and kill all nodes.
          _ <- maybeFacilities.traverse_(
            checkForkByLastSnapshotHash(_, status.lastSnapshotHash, config.forkConfirmationMinObservations)
          )
          _ <- maybeFacilities.traverse_(checkForkByConsensusConfigHash)

          // Evict peers with minority facilitatorsHash — deterministic since all healthy nodes
          // see the same declarations and identify the same minority.
          cleanFacilities = maybeFacilities.map { facilities =>
            val forkedPeers = ConsensusStateUpdater.identifyForkedPeers(
              status.facilitatorsHash,
              facilities.map { case (pid, f) => (pid, f.facilitatorsHash) }
            )
            if (forkedPeers.nonEmpty) facilities -- forkedPeers else facilities
          }

          _ <- (maybeFacilities, cleanFacilities).tupled.traverse_ {
            case (original, clean) =>
              val evicted = original.keySet -- clean.keySet
              ConsensusLog
                .warn(
                  logger,
                  Category.Fork,
                  state.key.show,
                  "n/a",
                  Event.ForkedPeersEvicted,
                  "evicted" -> evicted.size.toString,
                  "remaining" -> clean.size.toString,
                  "evictedPeers" -> evicted.toList.map(ConsensusLog.pid).mkString(",")
                )
                .whenA(evicted.nonEmpty)
          }

          result <- cleanFacilities.flatTraverse { facilities =>
            // Only fork-evicted peers (divergent facilitatorsHash) accumulate into
            // state.removedFacilitators — that set is consensus-agreed. Missing-declaration
            // peers remain in state.facilitators (they just don't participate this round);
            // evicting them would depend on local gossip-arrival timing and would diverge
            // across nodes. See dag-l0 mirror for full rationale.
            val forkEvictedPeers: Set[PeerId] = maybeFacilities match {
              case Some(orig) => orig.keySet -- facilities.keySet
              case None       => Set.empty
            }
            val updatedState: CurrencySnapshotConsensusState =
              if (forkEvictedPeers.nonEmpty)
                state.copy[CurrencySnapshotKey, CurrencySnapshotStatus, CurrencyConsensusOutcome, CurrencyConsensusKind](
                  facilitators = Facilitators(state.facilitators.value.filter(pid => !forkEvictedPeers.contains(pid))),
                  removedFacilitators = RemovedFacilitators(state.removedFacilitators.value ++ forkEvictedPeers)
                )
              else state
            maybeWaitForAdmissionCertificates(updatedState, resources, facilities).flatMap { waitForAcs =>
              if (waitForAcs)
                ConsensusLog
                  .info(
                    logger,
                    Category.Phase,
                    state.key.show,
                    "n/a",
                    Event.Admission,
                    "stage" -> "pre_proposal_grace",
                    "active" -> updatedState.roundStartFacilitators.value.size.toString,
                    "target" -> activeAdmissionTarget(updatedState).toString,
                    "candidates" -> openAdmissionCandidates(updatedState, facilities).size.toString,
                    "admissionVoteTargets" -> resources.admissionVotes.size.toString
                  )
                  .as(none[Transition])
              else toProposalsPhase(updatedState, facilities)
            }
          }
        } yield result

      private val AdmissionPreProposalGrace: FiniteDuration = 1500.millis

      private def activeAdmissionTarget(state: CurrencySnapshotConsensusState): Int =
        config.activeFacilitatorTarget.getOrElse(config.coreCommitteeSize.getOrElse(state.coreFacilitators.value.size))

      private def openAdmissionCandidates(
        state: CurrencySnapshotConsensusState,
        facilities: SortedMap[PeerId, Facility]
      ): Set[PeerId] = {
        val committee = state.roundStartFacilitators.value.toSet
        facilities.values.flatMap(_.candidates.value).filterNot(committee.contains).toSet
      }

      private def maybeWaitForAdmissionCertificates(
        state: CurrencySnapshotConsensusState,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind],
        facilities: SortedMap[PeerId, Facility]
      ): F[Boolean] =
        for {
          now <- Async[F].monotonic
          acs <- consensusStorage.getAssembledAdmissionCertificates(state.key)
          target = activeAdmissionTarget(state)
          activeBelowTarget = state.roundStartFacilitators.value.size < target
          hasAdmissionEvidence = openAdmissionCandidates(state, facilities).nonEmpty || resources.admissionVotes.nonEmpty
          graceOpen = now - state.createdAt < AdmissionPreProposalGrace
        } yield
          activeBelowTarget &&
            config.activeAdmissionMaxExpansionPerRound > 0 &&
            hasAdmissionEvidence &&
            acs.isEmpty &&
            graceOpen

      /** Caps the assembled admission certificates attached to an outgoing proposal at the validation limit (`acs_too_many` in
        * `validateProposalAcs`). Selection is delegated to the shared `AdmissionCertificateSelector` -- see its scaladoc for the
        * determinism + wedge rationale. Logs + counts only when the cap actually drops certificates. Mirrored verbatim in
        * `GlobalSnapshotConsensusStateAdvancer.capAssembledAdmissionCertificates` (keep in sync).
        */
      private def capAssembledAdmissionCertificates(
        key: CurrencySnapshotKey,
        assembled: Set[AdmissionCertificate]
      ): F[List[AdmissionCertificate]] = {
        val selection = AdmissionCertificateSelector.select(assembled, config.activeAdmissionMaxExpansionPerRound)
        ConsensusLog
          .info(
            logger,
            Category.Phase,
            key.show,
            "Leader",
            Event.Admission,
            "stage" -> "proposal_cap",
            "kept" -> selection.kept.map(c => ConsensusLog.pid(c.targetPeer)).mkString(","),
            "dropped" -> selection.dropped.map(c => ConsensusLog.pid(c.targetPeer)).mkString(",")
          )
          .productR(Metrics[F].incrementCounter("dag_consensus_admission_cert_capped_total"))
          .whenA(selection.dropped.nonEmpty)
          .as(selection.kept)
      }

      private def toProposalsPhase(
        state: CurrencySnapshotConsensusState,
        facilities: SortedMap[PeerId, Facility]
      ): F[Option[Transition]] = {
        val (candidates, triggers) = facilities.foldMap(f => (f.candidates.value, f.trigger.toList))

        // Compute hash UNION - include events ANY facilitator has, then sync missing
        val allHashSets = facilities.values.map(_.eventHashes).toList
        val unionHashes = allHashSets.reduceOption(_ union _).getOrElse(Set.empty[Hash])

        val trigger = pickMajority(triggers).getOrElse(EventTrigger)

        // v7 (mirror of dag-l0 `toProposalsPhase`): leader's positive observation of which
        // round-start facilitators sent a Facility this round, sorted at construction.
        // Bootstrap gate: empty during isInBootstrap so leader-build aligns with validation.
        val observedResponders: List[PeerId] =
          if (isInBootstrap(state)) List.empty
          else (facilities.keySet + selfId).toList.sorted
        // v15 (mirror of dag-l0): aggregate each facilitator's self-reported `selfHealthHint`.
        val observedSelfHealth: SortedMap[PeerId, SelfHealthHint] =
          if (isInBootstrap(state)) SortedMap.empty[PeerId, SelfHealthHint]
          else SortedMap.from(facilities.iterator.flatMap { case (pid, f) => f.selfHealthHint.map(pid -> _) })
        val committeeSize = state.roundStartFacilitators.value.size
        val responderRatio: Double =
          if (committeeSize > 0) observedResponders.size.toDouble / committeeSize.toDouble else 0.0

        // Build map of hash -> ALL peers who have it (for resilient fetching).
        // Previously used toMap which kept only the last peer per hash — if that peer was
        // unavailable the event was silently dropped. Now we retain all candidates and try
        // them in order until one succeeds.
        val hashToPeers: Map[Hash, List[PeerId]] = facilities.toList.flatMap {
          case (peerId, facility) => facility.eventHashes.map(_ -> peerId)
        }
          .groupMap(_._1)(_._2)

        // v19 phase 2 view-from-time anchor: compute median proposerClockMs across the
        // accepted Facility set, clamped against parent's consensusEndTime. Mirror of
        // dag-l0 toProposalsPhase; the helper returns None if below the strict-majority
        // threshold, in which case the next round will fall back to phase 1 view derivation.
        val parentEndTime: Option[Long] = state.lastOutcome.recentRoundEndTimes.lastOption.map(_._2)
        val outcomeEndTime: Option[Long] = ConsensusEndTime.compute(facilities.values, parentEndTime)
        // Type-arg-elaborated copy mirrors the forkEviction site above so the Kind
        // type parameter stays as `CurrencyConsensusKind` and doesn't widen to Nothing.
        val stateWithEndTime: CurrencySnapshotConsensusState =
          state.copy[CurrencySnapshotKey, CurrencySnapshotStatus, CurrencyConsensusOutcome, CurrencyConsensusKind](
            outcomeEndTime = outcomeEndTime
          )

        for {
          // Get local hashes and identify what we're missing
          localHashes <- eventMempool.getEventHashes
          missingHashes = unionHashes -- localHashes

          // Sync missing events from peers before building proposal
          _ <- syncMissingEvents(missingHashes, hashToPeers).whenA(missingHashes.nonEmpty)

          _ <- Metrics[F].updateGauge("dag_currency_consensus_observed_responders_count", observedResponders.size.toLong)
          _ <- Metrics[F].updateGauge("dag_currency_consensus_facility_quorum_ratio", responderRatio)

          result <- buildProposalTransition(stateWithEndTime, unionHashes, candidates, trigger, observedResponders, observedSelfHealth)
        } yield result
      }

      /** Sync missing events from peers who have them.
        *
        * For each missing hash we try each candidate peer in order, stopping as soon as one succeeds. This prevents a single unavailable
        * peer from causing an event to be silently skipped for the round.
        */
      // TODO: Group by first-available peer and fetch in parallel (parTraverse) like dag-l0 does.
      // Currently serializes per-hash requests; under load, adds latency proportional to missingHashes.size × RTT.
      private def syncMissingEvents(
        missingHashes: Set[Hash],
        hashToPeers: Map[Hash, List[PeerId]]
      ): F[Unit] =
        missingHashes.toList.traverse_ { hash =>
          hashToPeers.getOrElse(hash, Nil) match {
            case Nil   => Async[F].unit
            case peers =>
              // foldLeftM short-circuits on first success: once a peer returns the event, remaining peers are skipped
              peers
                .foldLeftM(false) { (found, peerId) =>
                  if (found) true.pure[F]
                  else fetchEventFromPeer(peerId, hash)
                }
                .flatMap { fetched =>
                  if (fetched) Async[F].unit
                  else logger.warn(s"[EventSync] Could not fetch hash ${hash.show.take(8)} from any of ${peers.size} peers")
                }
          }
        }

      /** Attempt to fetch a single event hash from a peer. Returns true if the event was successfully added to the mempool. */
      private def fetchEventFromPeer(peerId: PeerId, hash: Hash): F[Boolean] =
        clusterStorage.getPeer(peerId).flatMap {
          case None => false.pure[F]
          case Some(peer) =>
            eventGossipClient
              .requestEvents(IWantRequest(Set(hash)))
              .run(Peer.toP2PContext(peer))
              .flatMap { response =>
                response.events.traverse_ {
                  case (_, signedEvent) => eventMempool.add(signedEvent).void
                }.as(response.events.exists(_._1 === hash))
              }
              .handleErrorWith { err =>
                logger
                  .warn(s"[EventSync] Failed to fetch ${hash.show.take(8)} from peer ${peerId.show.take(8)}: ${err.getMessage}")
                  .as(false)
              }
        }

      private def buildProposalTransition(
        state: CurrencySnapshotConsensusState,
        commonHashes: Set[Hash],
        candidates: Set[PeerId],
        majorityTrigger: ConsensusTrigger,
        observedResponders: List[PeerId],
        observedSelfHealth: SortedMap[PeerId, SelfHealthHint]
      ): F[Option[Transition]] =
        HasherSelector[F].withCurrent { implicit hasher =>
          for {
            _ <- clearTimeTriggerIfNeeded(majorityTrigger)
            facilitatorsHash <- hashFacilitators(state)

            // Pull events from mempool using hash union across all facilitator declarations
            mempoolEvents <- eventMempool.getMultiple(commonHashes).map(_.values.map(_.signed.value).toSet)

            (artifact, context, _) <- createArtifact(state, majorityTrigger, mempoolEvents)

            // Do not remove accepted events at proposal time. A proposal can lose the round, or
            // different facilitators can propose the same event at adjacent ordinals. Events are
            // removed only after the winning artifact is finalized and persisted, so a proposed-but-
            // not-committed event survives in the mempool and is re-proposed next round.
            hash <- hashArtifact(artifact)
            isLeader = selfId === state.leader
            role = if (isLeader) "LEADER" else "FOLLOWER"
            withdrawnCount = state.withdrawnFacilitators.value.size
            _ <- logger.info(
              s"[CONSENSUS:$role] FACILITIES->PROPOSALS key=${state.key.show} ordinal=${artifact.ordinal.show} trigger=$majorityTrigger " +
                s"hash=${hash.show.take(8)}... facilitators=${state.facilitators.value.size} candidates=${candidates.size} " +
                s"leader=${state.leader.show.take(8)}... self=${selfId.show.take(8)}... view=${state.viewNumber}" +
                (if (withdrawnCount > 0) s" withdrawn=$withdrawnCount" else "") +
                s" facilitatorsHash=${facilitatorsHash.show.take(8)}... lastSnapshotHash=${state.lastOutcome.finished.snapshotHash.show
                    .take(8)}... entropy=${state.entropy.show.take(8)}..."
            )

            leaderLock <- consensusStorage.getVoteLock(state.key)
            // Mirror dag-l0: stale-VCC suppression gate + alpha.90 P0 #1 round-start bypass.
            // `clearResourcesPreservingDeclarations` preserves `assembledVccR` across retries; the
            // initialViewNumber-aware fetch gate keeps the cluster from consulting a stale 0->1
            // cert on a fresh seed-view round. See GlobalSnapshotConsensusStateAdvancer for the
            // full rationale.
            maybeAssembledVccRaw <-
              if (state.viewNumber > state.initialViewNumber) consensusStorage.getAssembledVcc(state.key)
              else none[ViewChangeCertificate].pure[F]
            maybeTimeoutCertificate <-
              if (state.viewNumber > state.initialViewNumber)
                consensusStorage
                  .getResources(state.key)
                  .map(_.timeoutCertificates.get((state.viewNumber.toLong - 1L, state.viewNumber.toLong)))
              else none[TimeoutCertificate].pure[F]
            maybeAssembledVcc = maybeTimeoutCertificate.fold {
              maybeAssembledVccRaw.filter(vcc => vcc.fromView === (state.viewNumber.toLong - 1L) && vcc.toView === state.viewNumber.toLong)
            }(_ => none[ViewChangeCertificate])
            vccHighestQc = maybeAssembledVcc.flatMap(_.highestQcInVcc)
            tcHighestQc = maybeTimeoutCertificate.flatMap { tc =>
              val qcs = tc.votes.toNonEmptyList.toList.flatMap(_.value.highestKnownQc)
              qcs.groupBy(_.view).toList.sortBy(_._1).lastOption.flatMap {
                case (_, atView) =>
                  val hashes = atView.map(_.proposalHash).toSet
                  if (hashes.size === 1) atView.headOption else None
              }
            }
            vccMismatch = isLeader && state.viewNumber > state.initialViewNumber && vccHighestQc.exists(_.proposalHash =!= hash)
            tcMismatch = isLeader && state.viewNumber > state.initialViewNumber && tcHighestQc.exists(_.proposalHash =!= hash)
            // v19 alpha.89: solo-mode bypass -- see dag-l0 mirror for full rationale.
            isSoloCore = state.coreFacilitators.value.size <= 1
            // alpha.90 P0 #1: round-start seed-view bypass -- see dag-l0 mirror.
            isRoundStartView = state.viewNumber === state.initialViewNumber
            viewCertMissing =
              isLeader && state.viewNumber > 0 && maybeAssembledVcc.isEmpty && maybeTimeoutCertificate.isEmpty && !isSoloCore && !isRoundStartView
            aborted = (isLeader && leaderLock
              .flatMap(_.lockedQc)
              .exists(_.proposalHash =!= hash)) || vccMismatch || tcMismatch || viewCertMissing
            _ <- logger
              .warn(
                s"[CONSENSUS:$role] Leader locked on different QC key=${state.key.show} lockedQcHash=${leaderLock
                    .flatMap(_.lockedQc)
                    .map(_.proposalHash.show.take(8))
                    .getOrElse("none")} proposingHash=${hash.show.take(8)}"
              )
              .whenA(isLeader && leaderLock.flatMap(_.lockedQc).exists(_.proposalHash =!= hash))
            _ <- logger
              .warn(
                s"[CONSENSUS:$role] Leader VCC highest-QC mismatch key=${state.key.show} view=${state.viewNumber} " +
                  s"qcHash=${vccHighestQc.map(_.proposalHash.show.take(8)).getOrElse("none")} " +
                  s"proposingHash=${hash.show.take(8)}"
              )
              .whenA(vccMismatch)
            _ <- logger
              .warn(
                s"[CONSENSUS:$role] Leader TC highest-QC mismatch key=${state.key.show} view=${state.viewNumber} " +
                  s"qcHash=${tcHighestQc.map(_.proposalHash.show.take(8)).getOrElse("none")} " +
                  s"proposingHash=${hash.show.take(8)}"
              )
              .whenA(tcMismatch)
            _ <- logger
              .warn(s"[CONSENSUS:$role] Leader view certificate missing for view>0 key=${state.key.show} view=${state.viewNumber}")
              .whenA(viewCertMissing)
          } yield
            if (aborted) none[Transition]
            else
              Transition(
                newState = state.copy(status =
                  CollectingProposals(
                    majorityTrigger,
                    ArtifactInfo(artifact, context, hash),
                    Candidates(candidates),
                    facilitatorsHash,
                    state.lastOutcome.finished.snapshotHash,
                    observedResponders,
                    observedSelfHealth
                  )
                ),
                sideEffect =
                  if (isLeader)
                    (for {
                      ecs <-
                        if (isInBootstrap(state)) Set.empty[EvictionCertificate].pure[F]
                        else consensusStorage.getAssembledEvictionCertificates(state.key)
                      acs <- consensusStorage
                        .getAssembledAdmissionCertificates(state.key)
                        .flatMap(capAssembledAdmissionCertificates(state.key, _))
                    } yield (ecs, acs)).flatMap {
                      case (ecs, acs) =>
                        spreadProposal(
                          state,
                          state.key,
                          hash,
                          facilitatorsHash,
                          artifact,
                          state.lastOutcome.finished.snapshotHash,
                          state.viewNumber.toLong,
                          maybeAssembledVcc,
                          maybeTimeoutCertificate,
                          ecs.toList,
                          acs,
                          observedResponders,
                          observedSelfHealth
                        )
                    }
                  else
                    Applicative[F].unit
              ).some
        }

      // =========================================================================
      // COLLECTING PROPOSALS → COLLECTING SIGNATURES
      // =========================================================================

      // ---- Leader-based proposal resolution ----
      // Only the leader spreads a Proposal + ConsensusArtifact. Non-leaders wait for the leader's
      // proposal to arrive via gossip, then validate the leader's artifact to obtain the context
      // needed for signing. If the leader's hash matches our own artifact, we use our local
      // ArtifactInfo directly (no extra validation needed).

      private def advanceFromProposals(
        state: CurrencySnapshotConsensusState,
        status: CollectingProposals,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      )(implicit hasher: Hasher[F]): F[Option[Transition]] = {
        // Guard: if we already withdrew from this round-and-view, don't re-enter validation.
        // See dag-l0 equivalent — state.withdrawnFacilitators is view-scoped (cleared by the
        // VCC-driven view-change reset in StateTransitions); resources.withdrawalsMap is not,
        // so OR-merging them kept the predicate sticky across views and wedged the node.
        val alreadyWithdrawn = state.withdrawnFacilitators.value.contains(selfId)

        if (alreadyWithdrawn)
          none[Transition].pure[F]
        else {
          val leader = state.leader
          val maybeLeaderProposal = resources.peerDeclarationsMap.get(leader).flatMap(_.proposal)

          maybeLeaderProposal match {
            case Some(leaderProposal) =>
              for {
                // Skip facilitatorsHash fork check when view > 0 (eviction), solo→multi transition,
                // or during joining grace period (peer quality scores haven't converged yet).
                lastSolo <- wasLastRoundSolo
                inGrace <- nodeStorage.isInJoiningGracePeriod
                // Single-source authoritative comparison: leader-vs-self. minObservations=1.
                _ <- checkForkByFacilitatorsHash(
                  SortedMap(leader -> leaderProposal),
                  status.facilitatorsHash,
                  minObservations = 1
                )(_.facilitatorsHash).whenA(!lastSolo && !inGrace)
                _ <- checkForkByLastSnapshotHash(
                  SortedMap(leader -> leaderProposal),
                  status.lastSnapshotHash,
                  minObservations = 1
                )
                result <- resolveLeaderProposal(state, status, resources, leaderProposal)
              } yield result
            case None =>
              if (selfId === state.leader)
                // Leader (possibly after view change) — spread proposal so peers can advance.
                // Include any assembled VCC for view > 0 so followers accept the re-spread.
                // Include any assembled EvictionCertificates so persistently-absent peers
                // get evicted at proposal acceptance (Phase B1). Symmetrically, include any
                // assembled AdmissionCertificates so probation peers ready at tip get
                // re-admitted (Phase B2).
                (for {
                  maybeVccRaw <-
                    if (state.viewNumber > state.initialViewNumber) consensusStorage.getAssembledVcc(state.key)
                    else none[ViewChangeCertificate].pure[F]
                  maybeTc <-
                    if (state.viewNumber > state.initialViewNumber)
                      consensusStorage
                        .getResources(state.key)
                        .map(_.timeoutCertificates.get((state.viewNumber.toLong - 1L, state.viewNumber.toLong)))
                    else none[TimeoutCertificate].pure[F]
                  maybeVcc = maybeTc.fold {
                    maybeVccRaw.filter(vcc => vcc.fromView === (state.viewNumber.toLong - 1L) && vcc.toView === state.viewNumber.toLong)
                  }(_ => none[ViewChangeCertificate])
                  ecs <-
                    if (isInBootstrap(state)) Set.empty[EvictionCertificate].pure[F]
                    else consensusStorage.getAssembledEvictionCertificates(state.key)
                  acs <- consensusStorage
                    .getAssembledAdmissionCertificates(state.key)
                    .flatMap(capAssembledAdmissionCertificates(state.key, _))
                } yield (maybeVcc, maybeTc, ecs, acs)).flatMap {
                  case (maybeVcc, maybeTc, ecs, acs) =>
                    logger.info(
                      s"[CONSENSUS:LEADER] Re-spreading proposal key=${state.key.show} hash=${status.proposalArtifactInfo.hash.show.take(8)}... " +
                        s"targets=${state.facilitators.value.size} view=${state.viewNumber}"
                    ) >>
                      spreadProposal(
                        state,
                        state.key,
                        status.proposalArtifactInfo.hash,
                        status.facilitatorsHash,
                        status.proposalArtifactInfo.artifact,
                        status.lastSnapshotHash,
                        state.viewNumber.toLong,
                        maybeVcc,
                        maybeTc,
                        ecs.toList,
                        acs,
                        // v7 codex turn 2 fix #2: re-spread reads observedResponders from the
                        // immutable status, not recomputed from current resources.
                        // v15: same rationale for observedSelfHealth.
                        status.observedResponders,
                        status.observedSelfHealth
                      ).as(none[Transition])
                }
              else
                none[Transition].pure[F]
          }
        }
      }

      /** Validate view/VCC invariants on an incoming proposal. Thin delegate to the shared `ProposalVccValidator.validate` helper -- see
        * GlobalSnapshotConsensusStateAdvancer for the full rationale on the alpha.90 P0 #1 + alpha.90 issue 2 changes that the helper
        * encapsulates. Effectful since v33: derives the shared quorum-denominator-shrink decision per validation (mirrors dag-l0).
        */
      private def validateProposalVcc(
        state: CurrencySnapshotConsensusState,
        proposal: Proposal,
        facilitatorsHash: Hash
      ): F[Either[ProposalRejection, Unit]] =
        quorumShrinkDecision(state).map { shrinkDecision =>
          ProposalVccValidator.validate(
            proposalView = proposal.view,
            proposalHash = proposal.hash,
            proposalVcc = proposal.vcc,
            proposalTimeoutCertificate = proposal.timeoutCertificate,
            initialViewNumber = state.initialViewNumber,
            coreSize = state.coreFacilitators.value.size,
            facilitatorsHash = facilitatorsHash,
            lastSnapshotHash = state.lastOutcome.finished.snapshotHash,
            eligibleFacilitators = state.eligibleFacilitators.value.toSet,
            roundStartFacilitators = state.roundStartFacilitators.value.toSet,
            peerQuality = state.lastOutcome.peerQuality.toMap,
            quorumThresholdFraction = config.quorumThresholdFraction,
            minParticipationObservations = config.minParticipationObservations,
            quorumShrink = Some(shrinkDecision)
          )
        }

      /** Verify cryptographic signatures on every `Signed[ViewChangeVote]` inside the VCC. Mirrors the dag-l0 helper. */
      // Phase B1 bootstrap gate. Mirrors dag-l0 / Phase 4 warmup.
      private def isInBootstrap(state: CurrencySnapshotConsensusState): Boolean =
        !state.lastOutcome.recentProofSizes.values.exists(_ >= config.bootstrapCompleteProofsThreshold)

      /** Validate structural invariants on every embedded `EvictionCertificate`. Mirrors dag-l0. */
      private def validateProposalEcs(
        state: CurrencySnapshotConsensusState,
        proposal: Proposal,
        facilitatorsHash: Hash
      ): Either[ProposalRejection, Unit] = {
        if (isInBootstrap(state) && proposal.evictionCertificates.nonEmpty)
          return Left(ProposalRejection(s"ecs_rejected_in_bootstrap count=${proposal.evictionCertificates.size}"))
        // v19: quorum threshold computed against the Core committee; target membership
        // remains the full round-start view. Mirror of dag-l0. Integer math via
        // `QuorumPolicy.fromFraction`.
        val n = state.coreFacilitators.value.size
        val q = math.max(1, QuorumPolicy.fromFraction(n, config.quorumThresholdFraction))
        val committee = state.roundStartFacilitators.value.toSet
        val expectedLastSnap: Hash = state.lastOutcome.finished.snapshotHash

        @scala.annotation.tailrec
        def loop(remaining: List[EvictionCertificate], seenTargets: Set[PeerId]): Either[ProposalRejection, Unit] =
          remaining match {
            case Nil => Right(())
            case cert :: tail =>
              if (seenTargets.contains(cert.targetPeer))
                Left(ProposalRejection(s"ecs_duplicate_target target=${cert.targetPeer.show.take(8)}"))
              else if (cert.facilitatorsHash =!= facilitatorsHash)
                Left(
                  ProposalRejection(
                    s"ecs_facilitators_mismatch target=${cert.targetPeer.show.take(8)} " +
                      s"certFacHash=${cert.facilitatorsHash.show.take(8)} ours=${facilitatorsHash.show.take(8)}"
                  )
                )
              else if (cert.lastSnapshotHash =!= expectedLastSnap)
                Left(
                  ProposalRejection(
                    s"ecs_last_snap_mismatch target=${cert.targetPeer.show.take(8)} " +
                      s"certLastSnap=${cert.lastSnapshotHash.show.take(8)} ours=${expectedLastSnap.show.take(8)}"
                  )
                )
              else if (!committee.contains(cert.targetPeer))
                Left(ProposalRejection(s"ecs_target_not_in_committee target=${cert.targetPeer.show.take(8)}"))
              else if (cert.votes.size < q)
                Left(ProposalRejection(s"ecs_under_quorum target=${cert.targetPeer.show.take(8)} votes=${cert.votes.size} required=$q"))
              else {
                val mismatched = cert.votes.toList.find { signed =>
                  signed.value.targetPeer =!= cert.targetPeer ||
                  signed.value.reason =!= cert.reason ||
                  signed.value.facilitatorsHash =!= cert.facilitatorsHash ||
                  signed.value.lastSnapshotHash =!= cert.lastSnapshotHash
                }
                mismatched match {
                  case Some(bad) =>
                    Left(
                      ProposalRejection(
                        s"ecs_vote_field_mismatch target=${cert.targetPeer.show.take(8)} voter=${bad.proofs.head.id.show.take(8)}"
                      )
                    )
                  case None =>
                    // Pool widens from the round-start committee to the union of
                    // `eligibleFacilitators` and historical participants in `lastOutcome.peerQuality`.
                    // See dag-l0 mirror for the full determinism analysis; both sides of the round
                    // derive the byte-identical pool via the shared WitnessPool helper.
                    val witnessPool = WitnessPool
                      .forTarget(
                        state.eligibleFacilitators.value.toSet,
                        state.lastOutcome.peerQuality.toMap,
                        config.minParticipationObservations,
                        cert.targetPeer
                      )
                      .union(state.roundStartFacilitators.value.toSet - cert.targetPeer)
                    val nonWitnessPoolVoter = cert.votes.toList.find(sv => !witnessPool.contains(sv.proofs.head.id.toPeerId))
                    nonWitnessPoolVoter match {
                      case Some(bad) =>
                        Left(
                          ProposalRejection(
                            s"ecs_voter_not_in_committee target=${cert.targetPeer.show.take(8)} voter=${bad.proofs.head.id.show.take(8)}"
                          )
                        )
                      case None => loop(tail, seenTargets + cert.targetPeer)
                    }
                }
              }
          }
        loop(proposal.evictionCertificates, Set.empty)
      }

      /** Verify every `Signed[EvictionVote]` inside every embedded `EvictionCertificate` has a valid crypto signature. Mirrors dag-l0. */
      private def verifyEcsSignatures(
        proposal: Proposal
      )(implicit hasher: Hasher[F]): F[Either[ProposalRejection, Unit]] =
        proposal.evictionCertificates.flatTraverse { cert =>
          cert.votes.toNonEmptyList.toList.traverse { signedVote =>
            signedVote.hasValidSignature[F].map {
              case true => Right(()): Either[ProposalRejection, Unit]
              case false =>
                Left(ProposalRejection(s"target=${cert.targetPeer.show.take(8)} voter=${signedVote.proofs.head.id.show.take(8)}"))
            }
          }
        }.map { results =>
          val invalid = results.collect { case Left(msg) => msg.code }
          if (invalid.isEmpty) Right(())
          else Left(ProposalRejection(s"ecs_invalid_signatures [${invalid.mkString("; ")}]"))
        }

      /** Validate structural invariants on every embedded `AdmissionCertificate`. Mirrors `validateProposalEcs` with symmetric checks for
        * re-admission targets. See dag-l0 mirror for full docstring.
        */
      private def validateProposalAcs(
        state: CurrencySnapshotConsensusState,
        proposal: Proposal,
        facilitatorsHash: Hash
      ): Either[ProposalRejection, Unit] = {
        val maxAdmissionCertificates = math.max(0, config.activeAdmissionMaxExpansionPerRound)
        if (proposal.admissionCertificates.size > maxAdmissionCertificates)
          return Left(
            ProposalRejection(
              s"acs_too_many count=${proposal.admissionCertificates.size} max=$maxAdmissionCertificates"
            )
          )
        // v19: quorum threshold computed against the Core committee; target membership
        // remains the full round-start view. Mirror of dag-l0. Integer math via
        // `QuorumPolicy.fromFraction`.
        val n = state.coreFacilitators.value.size
        val q = math.max(1, QuorumPolicy.fromFraction(n, config.quorumThresholdFraction))
        val committee = state.roundStartFacilitators.value.toSet
        val probation = state.lastOutcome.readmissionCountdown.keySet
        val penalized = state.lastOutcome.removalPenalties.filter(_._2 > 0).keySet
        val expectedLastSnap: Hash = state.lastOutcome.finished.snapshotHash

        @scala.annotation.tailrec
        def loop(remaining: List[AdmissionCertificate], seenTargets: Set[PeerId]): Either[ProposalRejection, Unit] =
          remaining match {
            case Nil => Right(())
            case cert :: tail =>
              if (seenTargets.contains(cert.targetPeer))
                Left(ProposalRejection(s"acs_duplicate_target target=${cert.targetPeer.show.take(8)}"))
              else if (cert.facilitatorsHash =!= facilitatorsHash)
                Left(
                  ProposalRejection(
                    s"acs_facilitators_mismatch target=${cert.targetPeer.show.take(8)} " +
                      s"certFacHash=${cert.facilitatorsHash.show.take(8)} ours=${facilitatorsHash.show.take(8)}"
                  )
                )
              else if (cert.lastSnapshotHash =!= expectedLastSnap)
                Left(
                  ProposalRejection(
                    s"acs_last_snap_mismatch target=${cert.targetPeer.show.take(8)} " +
                      s"certLastSnap=${cert.lastSnapshotHash.show.take(8)} ours=${expectedLastSnap.show.take(8)}"
                  )
                )
              else if (committee.contains(cert.targetPeer))
                Left(ProposalRejection(s"acs_target_already_in_committee target=${cert.targetPeer.show.take(8)}"))
              else if (penalized.contains(cert.targetPeer))
                Left(ProposalRejection(s"acs_target_penalized target=${cert.targetPeer.show.take(8)}"))
              else if (!probation.contains(cert.targetPeer) && cert.reason =!= AdmissionReason.ReadyAtTip)
                Left(ProposalRejection(s"acs_target_not_admissible target=${cert.targetPeer.show.take(8)} reason=${cert.reason.show}"))
              else if (cert.votes.size < q)
                Left(ProposalRejection(s"acs_under_quorum target=${cert.targetPeer.show.take(8)} votes=${cert.votes.size} required=$q"))
              else {
                val mismatched = cert.votes.toList.find { signed =>
                  signed.value.targetPeer =!= cert.targetPeer ||
                  signed.value.reason =!= cert.reason ||
                  signed.value.facilitatorsHash =!= cert.facilitatorsHash ||
                  signed.value.lastSnapshotHash =!= cert.lastSnapshotHash
                }
                mismatched match {
                  case Some(bad) =>
                    Left(
                      ProposalRejection(
                        s"acs_vote_field_mismatch target=${cert.targetPeer.show.take(8)} voter=${bad.proofs.head.id.show.take(8)}"
                      )
                    )
                  case None =>
                    // Symmetric widening with B1 -- see validateProposalEcs above.
                    val witnessPool = WitnessPool.forTarget(
                      state.eligibleFacilitators.value.toSet,
                      state.lastOutcome.peerQuality.toMap,
                      config.minParticipationObservations,
                      cert.targetPeer
                    )
                    val nonWitnessPoolVoter = cert.votes.toList.find(sv => !witnessPool.contains(sv.proofs.head.id.toPeerId))
                    nonWitnessPoolVoter match {
                      case Some(bad) =>
                        Left(
                          ProposalRejection(
                            s"acs_voter_not_in_committee target=${cert.targetPeer.show.take(8)} voter=${bad.proofs.head.id.show.take(8)}"
                          )
                        )
                      case None => loop(tail, seenTargets + cert.targetPeer)
                    }
                }
              }
          }
        loop(proposal.admissionCertificates, Set.empty)
      }

      /** Verify every `Signed[AdmissionVote]` inside every embedded `AdmissionCertificate` has a valid crypto signature. Mirrors dag-l0. */
      /** v7 (flaky-byzantine): observedResponders subset validation. See dag-l0 mirror for full rationale. */
      private def validateProposalObservedResponders(
        state: CurrencySnapshotConsensusState,
        proposal: Proposal
      ): Either[ProposalRejection, Unit] = {
        if (isInBootstrap(state) && proposal.observedResponders.nonEmpty)
          return Left(ProposalRejection(s"obs_resp_rejected_in_bootstrap count=${proposal.observedResponders.size}"))
        val committee = state.roundStartFacilitators.value.toSet
        val notInCommittee = proposal.observedResponders.toSet -- committee
        if (notInCommittee.nonEmpty)
          Left(ProposalRejection(s"obs_resp_not_in_committee count=${notInCommittee.size}"))
        else
          Right(())
      }

      private def verifyAcsSignatures(
        proposal: Proposal
      )(implicit hasher: Hasher[F]): F[Either[ProposalRejection, Unit]] =
        proposal.admissionCertificates.flatTraverse { cert =>
          cert.votes.toNonEmptyList.toList.traverse { signedVote =>
            signedVote.hasValidSignature[F].map {
              case true => Right(()): Either[ProposalRejection, Unit]
              case false =>
                Left(ProposalRejection(s"target=${cert.targetPeer.show.take(8)} voter=${signedVote.proofs.head.id.show.take(8)}"))
            }
          }
        }.map { results =>
          val invalid = results.collect { case Left(msg) => msg.code }
          if (invalid.isEmpty) Right(())
          else Left(ProposalRejection(s"acs_invalid_signatures [${invalid.mkString("; ")}]"))
        }

      private def verifyVccSignatures(vcc: ViewChangeCertificate)(implicit hasher: Hasher[F]): F[Either[ProposalRejection, Unit]] =
        vcc.votes.toNonEmptyList.traverse { signedVote =>
          signedVote.hasValidSignature[F].map {
            case true  => Right(()): Either[ProposalRejection, Unit]
            case false => Left(ProposalRejection(signedVote.proofs.head.id.show.take(8)))
          }
        }.map { results =>
          val invalidPeers = results.toList.collect { case Left(pid) => pid.code }
          if (invalidPeers.isEmpty) Right(())
          else Left(ProposalRejection(s"vcc_invalid_signatures peers=${invalidPeers.mkString(",")}"))
        }

      private def verifyTcSignatures(tc: TimeoutCertificate)(implicit hasher: Hasher[F]): F[Either[ProposalRejection, Unit]] =
        tc.votes.toNonEmptyList.toList.collect {
          case signedVote if signedVote.proofs.size != 1 =>
            signedVote.proofs.head.id.show.take(8)
        } match {
          case invalidProofCounts if invalidProofCounts.nonEmpty =>
            Applicative[F].pure(Left(ProposalRejection(s"tc_invalid_proof_count peers=${invalidProofCounts.mkString(",")}")))
          case _ =>
            tc.votes.toNonEmptyList.traverse { signedVote =>
              signedVote.hasValidSignature[F].map {
                case true  => Right(()): Either[ProposalRejection, Unit]
                case false => Left(ProposalRejection(signedVote.proofs.head.id.show.take(8)))
              }
            }.map { results =>
              val invalidPeers = results.toList.collect { case Left(pid) => pid.code }
              if (invalidPeers.isEmpty) Right(())
              else Left(ProposalRejection(s"tc_invalid_signatures peers=${invalidPeers.mkString(",")}"))
            }
        }

      private def resolveLeaderProposal(
        state: CurrencySnapshotConsensusState,
        status: CollectingProposals,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind],
        leaderProposal: Proposal
      )(implicit hasher: Hasher[F]): F[Option[Transition]] = {
        // Alpha.93 Fix A + Fix C: mirror dag-l0 GlobalSnapshotConsensusStateAdvancer. The stale-proposal
        // deadlock (project_alpha92_wedge_may21.md) is structural to the FSM, so the currency-l0 advancer
        // must apply the same self-heal -- prune the frozen leader slot when the rejection is the
        // proposalView < initialViewNumber AND vcc.isEmpty pattern, and increment the same metric.
        def logVccReject(rejection: ProposalRejection): F[Option[Transition]] = {
          val isStaleSlotPattern =
            leaderProposal.view < state.initialViewNumber.toLong &&
              leaderProposal.vcc.isEmpty &&
              leaderProposal.timeoutCertificate.isEmpty &&
              rejection.isMissingViewCert
          val maybePruneAndMeter =
            if (isStaleSlotPattern)
              Metrics[F].incrementCounter(
                "dag_currency_consensus_stale_proposal_rejection_total",
                Seq(Metrics.unsafeLabelName("peer_id") -> state.leader.show.take(8))
              ) >>
                consensusStorage.pruneStaleProposalSlots(state.key, state.initialViewNumber.toLong)
            else Applicative[F].unit
          logger
            .warn(s"[CONSENSUS] VCC validation failed key=${state.key.show} view=${state.viewNumber} reason=${rejection.code}") >>
            maybePruneAndMeter.as(none[Transition])
        }
        def logEcsReject(rejection: ProposalRejection): F[Option[Transition]] =
          logger
            .warn(s"[CONSENSUS] ECS validation failed key=${state.key.show} view=${state.viewNumber} reason=${rejection.code}")
            .as(none[Transition])
        def logAcsReject(rejection: ProposalRejection): F[Option[Transition]] =
          logger
            .warn(s"[CONSENSUS] ACS validation failed key=${state.key.show} view=${state.viewNumber} reason=${rejection.code}")
            .as(none[Transition])
        validateProposalVcc(state, leaderProposal, status.facilitatorsHash).flatMap {
          case Left(reason) => logVccReject(reason)
          case Right(_) =>
            val afterVccSig: F[Option[Transition]] = leaderProposal.vcc match {
              case Some(vcc) =>
                verifyVccSignatures(vcc).flatMap {
                  case Left(reason) => logVccReject(reason)
                  case Right(_)     => resolveLeaderProposalInner(state, status, resources, leaderProposal)
                }
              case None => resolveLeaderProposalInner(state, status, resources, leaderProposal)
            }
            val afterViewCertSig: F[Option[Transition]] = leaderProposal.timeoutCertificate match {
              case Some(tc) =>
                verifyTcSignatures(tc).flatMap {
                  case Left(reason) => logVccReject(reason)
                  case Right(_)     => afterVccSig
                }
              case None => afterVccSig
            }
            val afterEcs: F[Option[Transition]] =
              validateProposalEcs(state, leaderProposal, status.facilitatorsHash) match {
                case Left(reason) => logEcsReject(reason)
                case Right(_) =>
                  if (leaderProposal.evictionCertificates.isEmpty) afterViewCertSig
                  else
                    verifyEcsSignatures(leaderProposal).flatMap {
                      case Left(reason) => logEcsReject(reason)
                      case Right(_)     => afterViewCertSig
                    }
              }
            val afterAcs: F[Option[Transition]] =
              validateProposalAcs(state, leaderProposal, status.facilitatorsHash) match {
                case Left(reason) => logAcsReject(reason)
                case Right(_) =>
                  if (leaderProposal.admissionCertificates.isEmpty) afterEcs
                  else
                    verifyAcsSignatures(leaderProposal).flatMap {
                      case Left(reason) => logAcsReject(reason)
                      case Right(_)     => afterEcs
                    }
              }
            // v7 (flaky-byzantine): observedResponders subset validation. See dag-l0 mirror.
            validateProposalObservedResponders(state, leaderProposal) match {
              case Left(rejection) =>
                logger.warn(s"[CONSENSUS] obs_resp_validation failed key=${state.key.show} reason=${rejection.code}").as(none[Transition])
              case Right(_) =>
                // v19: observedResponders quorum gate computed against the Core committee.
                // Integer math via `QuorumPolicy.fromFraction`.
                val n = state.coreFacilitators.value.size
                val q = math.max(1, QuorumPolicy.fromFraction(n, config.quorumThresholdFraction))
                val below = leaderProposal.observedResponders.size < q && !isInBootstrap(state)
                logger
                  .warn(s"[CONSENSUS] obs_resp_below_quorum key=${state.key.show} size=${leaderProposal.observedResponders.size} quorum=$q")
                  .whenA(below) >> afterAcs
            }
        }
      }

      private def resolveLeaderProposalInner(
        state: CurrencySnapshotConsensusState,
        status: CollectingProposals,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind],
        leaderProposal: Proposal
      )(implicit hasher: Hasher[F]): F[Option[Transition]] = {
        val role = if (selfId === state.leader) "LEADER" else "FOLLOWER"
        if (leaderProposal.hash === status.proposalArtifactInfo.hash) {
          // Leader's artifact matches our own — use local ArtifactInfo (avoids re-validation)
          logger.info(
            s"[CONSENSUS:$role] PROPOSALS->SIGNATURES key=${state.key.show} matchesOwn=true hash=${leaderProposal.hash.show.take(8)}... " +
              s"trigger=${status.majorityTrigger} leader=${state.leader.show.take(8)}... self=${selfId.show.take(8)}... view=${state.viewNumber}"
          ) >>
            Metrics[F].incrementCounter("dag_consensus_proposal_affinity_match") >>
            buildSignatureTransition(
              state,
              status,
              status.proposalArtifactInfo,
              List(leaderProposal.hash),
              leaderProposal.vcc,
              leaderProposal.timeoutCertificate,
              leaderProposal.evictionCertificates,
              leaderProposal.admissionCertificates,
              leaderProposal.observedResponders,
              leaderProposal.observedSelfHealth
            )
        } else {
          // Leader proposed a different artifact — validate theirs
          resources.artifacts.get(leaderProposal.hash) match {
            case Some(leaderArtifact) =>
              validateLeaderArtifact(state, status, leaderArtifact, leaderProposal.hash).flatMap {
                case Right(leaderInfo) =>
                  logger.info(
                    s"[CONSENSUS:$role] PROPOSALS->SIGNATURES key=${state.key.show} matchesOwn=false " +
                      s"leaderHash=${leaderProposal.hash.show.take(8)}... ownHash=${status.proposalArtifactInfo.hash.show.take(8)}... " +
                      s"trigger=${status.majorityTrigger} leader=${state.leader.show.take(8)}... self=${selfId.show.take(8)}... view=${state.viewNumber}"
                  ) >>
                    Metrics[F].incrementCounter("dag_consensus_proposal_affinity_mismatch_accepted") >>
                    buildSignatureTransition(
                      state,
                      status,
                      leaderInfo,
                      List(leaderProposal.hash),
                      leaderProposal.vcc,
                      leaderProposal.timeoutCertificate,
                      leaderProposal.evictionCertificates,
                      leaderProposal.admissionCertificates,
                      leaderProposal.observedResponders,
                      leaderProposal.observedSelfHealth
                    )
                case Left(invalidArtifact) =>
                  val diffDetail = describeInvalidArtifact(invalidArtifact)
                  logger.warn(
                    s"[CONSENSUS:$role] Leader proposal FAILED validation key=${state.key.show} " +
                      s"leaderHash=${leaderProposal.hash.show.take(8)}... ownHash=${status.proposalArtifactInfo.hash.show.take(8)}... " +
                      s"leader=${state.leader.show.take(8)}... view=${state.viewNumber} reason=$diffDetail"
                  ) >>
                    logger.info(
                      s"[CONSENSUS:$role] Withdrawing from round key=${state.key.show} reason=proposal_validation_failed"
                    ) >>
                    gossip.spread(ConsensusWithdrawPeerDeclaration(state.key, CurrencyConsensusKind.Signature: CurrencyConsensusKind)) >>
                    Metrics[F].incrementCounter("dag_consensus_proposal_validation_failure") >>
                    Metrics[F].incrementCounter("dag_consensus_withdrawal_sent") >>
                    none[Transition].pure[F]
              }
            case None =>
              // Leader's artifact not yet received via gossip — wait
              none[Transition].pure[F]
          }
        }
      }

      private def validateLeaderArtifact(
        state: CurrencySnapshotConsensusState,
        status: CollectingProposals,
        artifact: CurrencySnapshotArtifact,
        hash: Hash
      )(implicit hasher: Hasher[F]): F[Either[InvalidArtifact, ArtifactInfo[CurrencySnapshotArtifact, CurrencySnapshotContext]]] =
        consensusFns
          .validateArtifact(
            state.lastOutcome.finished.signedMajorityArtifact,
            state.lastOutcome.finished.context,
            status.majorityTrigger,
            artifact,
            // Canonical round-start committee — matches createArtifact's read so leader and
            // validators accept/reject against the same facilitator set. See dag-l0 equivalent.
            state.roundStartFacilitators.value.toSet,
            getGlobalSnapshotByOrdinal,
            // v32 (stage 4): re-pack the evidence-only peerHistory from the validator's own
            // lastOutcome. See the dag-l0 mirror.
            Some(state.lastOutcome.signedArtifactPeerHistory)
          )
          .map {
            case Right((validatedArtifact, context)) =>
              ArtifactInfo(validatedArtifact, context, hash).asRight[InvalidArtifact]
            case Left(err) =>
              err.asLeft[ArtifactInfo[CurrencySnapshotArtifact, CurrencySnapshotContext]]
          }

      /** Produces a human-readable description of why the leader's artifact failed validation. */
      private def describeInvalidArtifact(err: InvalidArtifact): String = err match {
        case CurrencyArtifactMismatch(errors) =>
          val descriptions = errors.map {
            case SnapshotDifferentThanExpected(expected, actual) =>
              val diffs = List.newBuilder[String]
              if (expected.ordinal =!= actual.ordinal) diffs += s"ordinal(leader=${expected.ordinal.show},own=${actual.ordinal.show})"
              if (expected.height =!= actual.height) diffs += s"height(leader=${expected.height.show},own=${actual.height.show})"
              if (expected.blocks.size != actual.blocks.size) diffs += s"blocks(leader=${expected.blocks.size},own=${actual.blocks.size})"
              if (expected.rewards.size != actual.rewards.size)
                diffs += s"rewards(leader=${expected.rewards.size},own=${actual.rewards.size})"
              if (expected.lastSnapshotHash =!= actual.lastSnapshotHash)
                diffs += s"lastSnapshotHash(leader=${expected.lastSnapshotHash.show.take(8)},own=${actual.lastSnapshotHash.show.take(8)})"
              if (expected.tips =!= actual.tips) diffs += "tipsDiffer"
              if (expected.stateProof =!= actual.stateProof) diffs += "stateProofDiffers"
              val result = diffs.result()
              if (result.isEmpty) "SnapshotDifferentThanExpected(no field-level diff — possible serialization difference)"
              else s"SnapshotDifferentThanExpected[${result.mkString(",")}]"
            case SomeBlocksWereNotAccepted(awaiting, rejected) =>
              s"SomeBlocksWereNotAccepted(awaiting=${awaiting.size},rejected=${rejected.size})"
            case other =>
              other.show
          }
          s"CurrencyArtifactMismatch[${descriptions.mkString(";")}]"
        case other =>
          other.getClass.getSimpleName
      }

      private def buildSignatureTransition(
        state: CurrencySnapshotConsensusState,
        status: CollectingProposals,
        majorityInfo: ArtifactInfo[CurrencySnapshotArtifact, CurrencySnapshotContext],
        proposalHashes: List[Hash],
        leaderVcc: Option[ViewChangeCertificate] = None,
        leaderTimeoutCertificate: Option[TimeoutCertificate] = None,
        leaderEvictionCerts: List[EvictionCertificate] = List.empty,
        leaderAdmissionCerts: List[AdmissionCertificate] = List.empty,
        leaderObservedResponders: List[PeerId] = List.empty,
        leaderObservedSelfHealth: SortedMap[PeerId, SelfHealthHint] = SortedMap.empty
      )(implicit hasher: Hasher[F]): F[Option[Transition]] = {
        val evictedTargets: Set[PeerId] =
          if (isInBootstrap(state)) Set.empty
          else leaderEvictionCerts.map(_.targetPeer).toSet
        val postEvictionFacilitators =
          if (evictedTargets.isEmpty) state.facilitators
          else Facilitators(state.facilitators.value.filterNot(evictedTargets.contains))
        val postEvictionRemoved =
          if (evictedTargets.isEmpty) state.removedFacilitators
          else RemovedFacilitators(state.removedFacilitators.value ++ evictedTargets)
        // Defense in depth (mirrors dag-l0): validateProposalAcs already rejected any proposal
        // carrying more than the cap (`acs_too_many`), so this selection is a no-op on every
        // honest path. Applying the SAME shared deterministic selection here guarantees that
        // even if a future refactor ever lets an over-cap proposal through validation, every
        // node still admits the same capped subset.
        val admissionSelection =
          AdmissionCertificateSelector.select(leaderAdmissionCerts, config.activeAdmissionMaxExpansionPerRound)
        val admittedTargets: Set[PeerId] =
          admissionSelection.kept.map(_.targetPeer).toSet
        val postAdmissionAdmitted =
          if (admittedTargets.isEmpty) state.admittedFacilitators
          else AdmittedFacilitators(state.admittedFacilitators.value ++ admittedTargets)
        val acceptedTimeoutVoters: SortedSet[PeerId] =
          leaderTimeoutCertificate
            .map(tc => SortedSet.from(tc.votes.toNonEmptyList.toList.map(_.proofs.head.id.toPeerId)))
            .getOrElse(SortedSet.empty[PeerId])
        for {
          facilitatorsHash <- postEvictionFacilitators.value.hash
          view = state.viewNumber.toLong
          localLock <- consensusStorage.getVoteLock(state.key)
          effectiveLockedQc = VoteLock.maxByView(
            localLock.flatMap(_.lockedQc),
            leaderVcc.flatMap(_.highestQcInVcc)
          )
          tryLock <- consensusStorage.tryLockVote(state.key, view, majorityInfo.hash, effectiveLockedQc)
          result <- tryLock match {
            case Left(rejection) =>
              logger
                .warn(
                  s"[CONSENSUS] Vote lock rejected key=${state.key.show} view=$view hash=${majorityInfo.hash.show
                      .take(8)} rejection=${rejection.code} reason=${rejection.message}"
                )
                .as(none[Transition])
            case Right(_) =>
              for {
                // Sign the proposal artifact hash directly. See dag-l0 mirror for rationale: widening the
                // signing domain would break Signed[artifact] verification in toFinishedPhase. Safety against
                // double-signing is enforced at the VoteLock gate above.
                signature <- Signature.fromHash(keyPair.getPrivate, majorityInfo.hash)
                // Self-store the MajoritySignature locally. See dag-l0 mirror — closes the
                // ord-10 fast-path race where our own signature lands via gossip round-trip
                // a few ms after quorum from other peers crosses the threshold.
                selfMajoritySig = MajoritySignature(
                  signature,
                  facilitatorsHash,
                  state.lastOutcome.finished.snapshotHash,
                  view,
                  majorityInfo.hash
                )
                _ <- consensusStorage.addSignature(selfId, state.key, selfMajoritySig).void
                _ <- recordProposalAffinity(proposalHashes, status.proposalArtifactInfo.hash)
                _ <- logger
                  .info(
                    s"[CONSENSUS] Applied ${evictedTargets.size} evictions key=${state.key.show} " +
                      s"targets=${evictedTargets.toList.map(_.show.take(8)).mkString(",")}"
                  )
                  .whenA(evictedTargets.nonEmpty)
                _ <- logger
                  .info(
                    s"[CONSENSUS] Applied ${admittedTargets.size} admissions key=${state.key.show} " +
                      s"targets=${admittedTargets.toList.map(_.show.take(8)).mkString(",")}"
                  )
                  .whenA(admittedTargets.nonEmpty)
                // Should never fire (validation rejects over-cap proposals); a hit means an
                // over-cap proposal slipped past validateProposalAcs.
                _ <- (ConsensusLog
                  .info(
                    logger,
                    Category.Phase,
                    state.key.show,
                    "n/a",
                    Event.Admission,
                    "stage" -> "apply_cap",
                    "kept" -> admissionSelection.kept.map(c => ConsensusLog.pid(c.targetPeer)).mkString(","),
                    "dropped" -> admissionSelection.dropped.map(c => ConsensusLog.pid(c.targetPeer)).mkString(",")
                  ) >> Metrics[F].incrementCounter("dag_consensus_admission_cert_capped_total"))
                  .whenA(admissionSelection.dropped.nonEmpty)
              } yield
                Transition(
                  newState = state.copy(
                    facilitators = postEvictionFacilitators,
                    removedFacilitators = postEvictionRemoved,
                    admittedFacilitators = postAdmissionAdmitted,
                    // Controller evidence stage 1: certificate-applied eviction targets only.
                    // See dag-l0 mirror.
                    certifiedEvictionTargets = state.certifiedEvictionTargets ++ evictedTargets,
                    // v7 codex turn 2 fix #5: REPLACE on accept (not union). See dag-l0 mirror.
                    observedResponders = ObservedResponders(leaderObservedResponders.toSet),
                    // v15: REPLACE on accept; see dag-l0 mirror.
                    observedSelfHealth = ObservedSelfHealth(leaderObservedSelfHealth),
                    acceptedTimeoutCertificateVoters = acceptedTimeoutVoters,
                    status = CollectingSignatures(
                      majorityInfo,
                      status.majorityTrigger,
                      status.candidates,
                      facilitatorsHash,
                      state.lastOutcome.finished.snapshotHash
                    )
                  ),
                  sideEffect = spreadSignature(
                    state,
                    state.key,
                    signature,
                    facilitatorsHash,
                    state.lastOutcome.finished.snapshotHash,
                    view,
                    majorityInfo.hash
                  )
                ).some
          }
        } yield result
      }

      /** Canonical byte encoding of the signing domain for MajoritySignature: `(key, view, proposalHash, facilitatorsHash)`. */
      private def canonicalSignBytes(
        key: CurrencySnapshotKey,
        view: Long,
        proposalHash: Hash,
        facilitatorsHash: Hash
      ): Array[Byte] = {
        val sb = new StringBuilder(128)
        sb.append("MS|")
          .append(key.show)
          .append('|')
          .append(view)
          .append('|')
          .append(proposalHash.value)
          .append('|')
          .append(facilitatorsHash.value)
        sb.toString.getBytes("UTF-8")
      }

      // =========================================================================
      // COLLECTING SIGNATURES → COLLECTING BINARY SIGNATURES
      // =========================================================================

      private def advanceFromSignatures(
        state: CurrencySnapshotConsensusState,
        status: CollectingSignatures,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      ): F[Option[Transition]] =
        for {
          maybeSignatures <- maybeGetAllDeclarations(state, resources)(_.signature)
          maybeFacilities <- maybeGetAllDeclarations(state, resources)(_.facility)
          // Skip facilitatorsHash fork check when view > 0 (eviction), solo→multi transition,
          // or during joining grace period (peer quality scores haven't converged yet).
          lastSolo2 <- wasLastRoundSolo
          inGrace2 <- nodeStorage.isInJoiningGracePeriod
          _ <- maybeSignatures
            .traverse_(
              checkForkByFacilitatorsHash(_, status.facilitatorsHash, config.forkConfirmationMinObservations)(_.facilitatorsHash)
            )
            .whenA(!lastSolo2 && !inGrace2)
          _ <- maybeSignatures.traverse_(
            checkForkByLastSnapshotHash(_, status.lastSnapshotHash, config.forkConfirmationMinObservations)
          )
          maybeGlobalOrd = extractGlobalSnapshotOrdinal(maybeFacilities)
          result <- (maybeGlobalOrd, maybeSignatures) match {
            case (Some(globalOrd), Some(signatures)) =>
              HasherSelector[F].withCurrent { implicit hs =>
                toBinarySignaturesPhase(state, status, globalOrd, signatures)
              }
            case _ =>
              none[Transition].pure[F]
          }
        } yield result

      private def extractGlobalSnapshotOrdinal(maybeFacilities: Option[SortedMap[PeerId, Facility]]): Option[SnapshotOrdinal] =
        maybeFacilities
          .map(_.values.map(_.lastGlobalSnapshotOrdinal).toList)
          .flatMap(pickMajority(_))

      private def toBinarySignaturesPhase(
        state: CurrencySnapshotConsensusState,
        status: CollectingSignatures,
        globalOrdinal: SnapshotOrdinal,
        signatures: SortedMap[PeerId, MajoritySignature]
      )(implicit hasher: Hasher[F]): F[Option[Transition]] = {
        val proofs = signatures.map { case (id, sig) => SignatureProof(PeerId._Id.get(id), sig.signature) }.toList

        for {
          valid <- proofs.filterA(verifySignatureProof(status.majorityArtifactInfo.hash, _))
          _ <- logInvalidSignatures(state.key, proofs.size, valid.size)
          role = if (selfId === state.leader) "LEADER" else "FOLLOWER"
          _ <- logger.info(
            s"[CONSENSUS:$role] SIGNATURES->BINARY_SIGNATURES key=${state.key.show} signatures=${valid.size}/${proofs.size} " +
              s"hash=${status.majorityArtifactInfo.hash.show.take(8)}... trigger=${status.majorityTrigger} globalOrdinal=${globalOrdinal.show} " +
              s"leader=${state.leader.show.take(8)}... self=${selfId.show.take(8)}... view=${state.viewNumber}"
          )
          result <- buildBinaryTransition(state, status, valid, globalOrdinal)
        } yield result
      }

      private def buildBinaryTransition(
        state: CurrencySnapshotConsensusState,
        status: CollectingSignatures,
        validSignatures: List[SignatureProof],
        globalOrdinal: SnapshotOrdinal
      )(implicit hasher: Hasher[F]): F[Option[Transition]] =
        // Canonical committee hash — see dag-l0 equivalent.
        state.roundStartFacilitators.value.hash.flatMap { facilitatorsHash =>
          NonEmptySet.fromSet(validSignatures.toSortedSet).traverse { signaturesNes =>
            val signedArtifact = Signed(status.majorityArtifactInfo.artifact, signaturesNes)
            val stakingAddress = fetchStakingAddress(state.lastOutcome.finished.context.snapshotInfo)

            stateChannelSnapshotService
              .createBinary(signedArtifact, state.lastOutcome.finished.binaryArtifactHash, globalOrdinal.some, stakingAddress)
              .flatMap { signedBinary =>
                // Self-store the BinarySignature locally — same rationale as the
                // MajoritySignature self-store in dag-l0. Without this, our own
                // BinarySignature only enters resources via gossip round-trip; if
                // three other peers' binary sigs cross quorum in 1-3ms, our node
                // finalizes the currency round without its own signature (the
                // currency analogue of the ord-10 race). Currently
                // masked in dev by quorumThresholdFraction=1.0, but becomes
                // active on any cluster configured with supermajority quorum.
                val selfBinarySig = BinarySignature(
                  signedBinary.proofs.head.signature,
                  facilitatorsHash,
                  state.lastOutcome.finished.snapshotHash
                )
                consensusStorage
                  .addBinarySignature(selfId, state.key, selfBinarySig)
                  .as(
                    Transition(
                      newState = state.copy(status =
                        CollectingBinarySignatures(
                          signedArtifact,
                          status.majorityArtifactInfo.context,
                          signedBinary.value,
                          status.majorityTrigger,
                          status.candidates,
                          facilitatorsHash,
                          state.lastOutcome.finished.snapshotHash
                        )
                      ),
                      sideEffect = spreadBinarySignature(
                        state,
                        state.key,
                        signedBinary.proofs.head.signature,
                        facilitatorsHash,
                        state.lastOutcome.finished.snapshotHash
                      )
                    )
                  )
              }
          }
        }

      // =========================================================================
      // COLLECTING BINARY SIGNATURES → FINISHED
      // =========================================================================

      private def advanceFromBinarySignatures(
        state: CurrencySnapshotConsensusState,
        status: CollectingBinarySignatures,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      ): F[Option[Transition]] =
        for {
          maybeBinarySignatures <- maybeGetAllDeclarations(state, resources)(_.binarySignature)
          // Skip facilitatorsHash fork check when view > 0 (eviction), solo→multi transition,
          // or during joining grace period (peer quality scores haven't converged yet).
          lastSolo3 <- wasLastRoundSolo
          inGrace3 <- nodeStorage.isInJoiningGracePeriod
          _ <- maybeBinarySignatures
            .traverse_(
              checkForkByFacilitatorsHash(_, status.facilitatorsHash, config.forkConfirmationMinObservations)(_.facilitatorsHash)
            )
            .whenA(!lastSolo3 && !inGrace3)
          _ <- maybeBinarySignatures.traverse_(
            checkForkByLastSnapshotHash(_, status.lastSnapshotHash, config.forkConfirmationMinObservations)
          )
          result <- maybeBinarySignatures.flatTraverse(toFinishedPhase(state, status, _))
        } yield result

      private def toFinishedPhase(
        state: CurrencySnapshotConsensusState,
        status: CollectingBinarySignatures,
        signatures: SortedMap[PeerId, BinarySignature]
      ): F[Option[Transition]] =
        HasherSelector[F].withCurrent { implicit hasher =>
          val proofs = signatures.map { case (id, bs) => SignatureProof(PeerId._Id.get(id), bs.signature) }.toList

          for {
            binaryHash <- status.binary.hash
            valid <- proofs.filterA(verifySignatureProof(binaryHash, _))
            _ <- logInvalidBinarySignatures(state.key, proofs.size, valid.size)
            role = if (selfId === state.leader) "LEADER" else "FOLLOWER"
            _ <- logger.info(
              s"[CONSENSUS:$role] BINARY_SIGNATURES->FINISHED key=${state.key.show} ordinal=${status.signedMajorityArtifact.ordinal.show} " +
                s"binarySignatures=${valid.size}/${proofs.size} binaryHash=${binaryHash.show.take(8)}... " +
                s"trigger=${status.majorityTrigger} leader=${state.leader.show.take(8)}... self=${selfId.show.take(8)}... view=${state.viewNumber}"
            )
            result <- buildFinishedTransition(state, status, valid)
          } yield result
        }

      private def buildFinishedTransition(
        state: CurrencySnapshotConsensusState,
        status: CollectingBinarySignatures,
        validSignatures: List[SignatureProof]
      )(implicit hasher: Hasher[F]): F[Option[Transition]] =
        for {
          // Canonical committee hash — see dag-l0 equivalent.
          facilitatorsHash <- state.roundStartFacilitators.value.hash
          // Use the artifact hash (without signatures) for determinism across nodes.
          // signedMajorityArtifact.hash includes signatures, which can differ per node
          // when quorum < total, causing non-deterministic snapshotHash.
          snapshotHash <- status.signedMajorityArtifact.value.hash

          result <- NonEmptySet.fromSet(validSignatures.toSortedSet).traverse { signaturesNes =>
            val finalSignedBinary = Signed(status.binary, signaturesNes)
            finalSignedBinary.toHashed.map { hashedBinary =>
              Transition(
                newState = state.copy(status =
                  Finished(
                    status.signedMajorityArtifact,
                    hashedBinary.hash,
                    status.context,
                    status.majorityTrigger,
                    status.candidates,
                    facilitatorsHash,
                    snapshotHash
                  )
                ),
                sideEffect = persistAndGossip(status.signedMajorityArtifact, hashedBinary, state, status.context)
              )
            }
          }
        } yield result

      // Canonical committee hash — see dag-l0 equivalent.
      private def hashFacilitators(state: CurrencySnapshotConsensusState): F[Hash] =
        HasherSelector[F].withCurrent(implicit h => state.roundStartFacilitators.value.hash)

      private def hashArtifact(artifact: CurrencySnapshotArtifact): F[Hash] =
        HasherSelector[F].withCurrent(implicit h => artifact.hash)

      private def createArtifact(
        state: CurrencySnapshotConsensusState,
        trigger: ConsensusTrigger,
        events: Set[CurrencySnapshotEvent]
      )(implicit hasher: Hasher[F]): F[(CurrencySnapshotArtifact, CurrencySnapshotContext, Set[CurrencySnapshotEvent])] =
        consensusFns.createProposalArtifact(
          state.key,
          state.lastOutcome.finished.signedMajorityArtifact,
          state.lastOutcome.finished.context,
          hasher,
          trigger,
          events,
          // Canonical round-start committee — matches validateLeaderArtifact.
          state.roundStartFacilitators.value.toSet,
          getGlobalSnapshotByOrdinal,
          // v32 (stage 4): evidence-only signed peerHistory. See the dag-l0 mirror.
          Some(state.lastOutcome.signedArtifactPeerHistory)
        )

      private val selfId: PeerId = PeerId.fromPublic(keyPair.getPublic)

      /** Spread proposal — only called by the leader. Uses direct push to all facilitators. */
      private def spreadProposal(
        state: CurrencySnapshotConsensusState,
        key: CurrencySnapshotKey,
        hash: Hash,
        facilitatorsHash: Hash,
        artifact: CurrencySnapshotArtifact,
        lastSnapshotHash: Hash,
        view: Long = 0L,
        vcc: Option[ViewChangeCertificate] = None,
        timeoutCertificate: Option[TimeoutCertificate] = None,
        evictionCertificates: List[EvictionCertificate] = List.empty,
        admissionCertificates: List[AdmissionCertificate] = List.empty,
        observedResponders: List[PeerId] = List.empty,
        observedSelfHealth: SortedMap[PeerId, SelfHealthHint] = SortedMap.empty
      ): F[Unit] = {
        val sortedEcs = evictionCertificates.sorted(EvictionCertificate.ordering)
        val sortedAcs = admissionCertificates.sorted(AdmissionCertificate.ordering)
        val sortedObs = observedResponders.distinct.sorted
        val declaration =
          ConsensusPeerDeclaration(
            key,
            Proposal(
              hash = hash,
              facilitatorsHash = facilitatorsHash,
              lastSnapshotHash = lastSnapshotHash,
              view = view,
              vcc = vcc,
              timeoutCertificate = timeoutCertificate,
              evictionCertificates = sortedEcs,
              admissionCertificates = sortedAcs,
              observedResponders = sortedObs,
              observedSelfHealth = observedSelfHealth
            )
          )
        val targets = state.facilitators.value.toSet

        gossip.spreadDirect(declaration, targets) >>
          gossip.spreadCommon(ConsensusArtifact(key, artifact))
      }

      private def spreadSignature(
        state: CurrencySnapshotConsensusState,
        key: CurrencySnapshotKey,
        signature: Signature,
        facilitatorsHash: Hash,
        lastSnapshotHash: Hash,
        view: Long,
        proposalHash: Hash
      ): F[Unit] = {
        val declaration =
          ConsensusPeerDeclaration(key, MajoritySignature(signature, facilitatorsHash, lastSnapshotHash, view, proposalHash))
        gossip.spreadDirect(declaration, state.facilitators.value.toSet)
      }

      private def spreadBinarySignature(
        state: CurrencySnapshotConsensusState,
        key: CurrencySnapshotKey,
        signature: Signature,
        facilitatorsHash: Hash,
        lastSnapshotHash: Hash
      ): F[Unit] = {
        val declaration = ConsensusPeerDeclaration(key, BinarySignature(signature, facilitatorsHash, lastSnapshotHash))
        gossip.spreadDirect(declaration, state.facilitators.value.toSet)
      }

      private def persistAndGossip(
        signedArtifact: Signed[CurrencySnapshotArtifact],
        hashedBinary: Hashed[StateChannelSnapshotBinary],
        state: CurrencySnapshotConsensusState,
        context: CurrencySnapshotContext
      )(implicit hasher: Hasher[F]): F[Unit] =
        stateChannelSnapshotService
          .consume(
            signedArtifact,
            hashedBinary,
            state.lastOutcome.facilitators.value,
            context
          )
          .ifM(
            // Persist succeeded: this is the winning, persisted artifact, so clear the events it
            // committed from the mempool (active and suspended). Mirror of dag-l0.
            clearCommittedEvents(signedArtifact.value) >>
              recordMetrics(signedArtifact, hashedBinary, context) >>
              notifyDataApplication(signedArtifact),
            ConsensusLog.error(logger, Category.Lifecycle, signedArtifact.ordinal.show, "n/a", Event.PersistFailed) >>
              recordMetrics(signedArtifact, hashedBinary, context) >>
              notifyDataApplication(signedArtifact)
          )

      private def clearCommittedEvents(artifact: CurrencySnapshotArtifact): F[Unit] =
        committedEvents(artifact).flatMap { committed =>
          if (committed.isEmpty) Applicative[F].unit
          else
            for {
              activeHashes <- eventMempool.getEventHashes
              activeEvents <- eventMempool.getMultiple(activeHashes)
              suspended <- eventMempool.suspendedSnapshot(Int.MaxValue)
              activeCommittedHashes = activeEvents.collect {
                case (hash, hashed) if committed.contains(hashed.signed.value) => hash
              }.toSet
              suspendedCommittedHashes = suspended.entries.collect {
                case (hash, entry) if committed.contains(entry.hashed.signed.value) => hash
              }.toSet
              committedHashes = activeCommittedHashes | suspendedCommittedHashes
              _ <- eventMempool.clearIncluded(committedHashes).whenA(committedHashes.nonEmpty)
              _ <- ConsensusLog
                .info(
                  logger,
                  Category.Lifecycle,
                  artifact.ordinal.show,
                  "n/a",
                  Event.CommittedMempoolEventsCleared,
                  "committedMempoolEventsCleared" -> committedHashes.size.toString
                )
                .whenA(committedHashes.nonEmpty)
            } yield ()
        }

      // Reconstruct the mempool-originating events that this finalized artifact committed, from its
      // per-round accepted-event deltas (NOT accumulated state), so we never clear still-pending
      // events. Mirror of dag-l0; data-application blocks are stored as serialized bytes in the
      // artifact, so they are deserialized (best-effort) to recover their events, matching
      // CurrencySnapshotValidator. ForceEventTrigger is a synchronization trigger that is never
      // carried as accepted artifact content, so it is intentionally not enumerated here.
      private def committedEvents(artifact: CurrencySnapshotArtifact): F[Set[CurrencySnapshotEvent]] = {
        val blockEvents = artifact.blocks.unsorted.toList.map(_.block).map(BlockEvent(_))
        val allowSpendEvents = artifact.allowSpendBlocks.toList.flatMap(_.toList.map(AllowSpendBlockEvent(_)))
        val tokenLockEvents = artifact.tokenLockBlocks.toList.flatMap(_.toList.map(TokenLockBlockEvent(_)))
        val messageEvents = artifact.messages.toList.flatMap(_.toList.map(CurrencyMessageEvent(_)))
        val globalSnapshotSyncEvents = artifact.globalSnapshotSyncs.toList.flatMap(_.toList.map(GlobalSnapshotSyncEvent(_)))

        val dataApplicationEvents: F[List[CurrencySnapshotEvent]] =
          maybeDataApplication.flatTraverse { service =>
            artifact.dataApplication.map(_.blocks).traverse(_.traverse(service.deserializeBlock))
          }.map(_.map(_.flatMap(_.toOption).map(DataApplicationBlockEvent(_))).getOrElse(List.empty))

        dataApplicationEvents.map { daEvents =>
          (blockEvents ++ allowSpendEvents ++ tokenLockEvents ++ messageEvents ++ globalSnapshotSyncEvents ++ daEvents).toSet
        }
      }

      private def recordMetrics(
        signed: Signed[CurrencySnapshotArtifact],
        hashedBinary: Hashed[StateChannelSnapshotBinary],
        context: CurrencySnapshotContext
      ): F[Unit] = {
        val metagraphTag: Metrics.TagSeq =
          Seq((Metrics.unsafeLabelName("metagraph_address"), context.address.show))

        // Blocks & transactions
        val allTransactions = signed.blocks.toList.flatMap(_.block.transactions.toList)
        val txCount = allTransactions.size
        val txAmountTotal = allTransactions.map(_.amount.value.value).sum
        val txFeeTotal = allTransactions.map(_.fee.value.value).sum

        // Rewards
        val rewardsCount = signed.rewards.size
        val rewardsAmountTotal = signed.rewards.toList.map(_.amount.value.value).sum

        // Tips
        val activeTips = signed.tips.remainedActive.size + signed.blocks.size
        val deprecatedTips = signed.tips.deprecated.size

        // Extended fields
        val messagesCount = signed.messages.map(_.size).getOrElse(0)
        val globalSnapshotSyncsCount = signed.globalSnapshotSyncs.map(_.size).getOrElse(0)
        val artifactsCount = signed.artifacts.map(_.size).getOrElse(0)

        // Fee transactions
        val feeTxList = signed.feeTransactions.map(_.toList).getOrElse(List.empty)
        val feeTransactionsCount = feeTxList.size
        val feeTransactionsAmountTotal = feeTxList.map(_.amount.value.value).sum

        // AllowSpend
        val allowSpendBlocks = signed.allowSpendBlocks.map(_.toList).getOrElse(List.empty)
        val allowSpendBlocksCount = allowSpendBlocks.size
        val allAllowSpends = allowSpendBlocks.flatMap(_.transactions.toList)
        val allowSpendTxCount = allAllowSpends.size
        val allowSpendAmountTotal = allAllowSpends.map(_.amount.value.value).sum
        val allowSpendFeeTotal = allAllowSpends.map(_.fee.value.value).sum

        // TokenLock
        val tokenLockBlocks = signed.tokenLockBlocks.map(_.toList).getOrElse(List.empty)
        val tokenLockBlocksCount = tokenLockBlocks.size
        val allTokenLocks = tokenLockBlocks.flatMap(_.tokenLocks.toList)
        val tokenLockTxCount = allTokenLocks.size
        val tokenLockAmountTotal = allTokenLocks.map(_.amount.value.value).sum
        val tokenLockFeeTotal = allTokenLocks.map(_.fee.value.value).sum

        // Data application
        val dataAppOnChainStateBytes = signed.dataApplication.map(_.onChainState.length.toLong).getOrElse(0L)
        val dataAppBlocksCount = signed.dataApplication.map(_.blocks.size).getOrElse(0)
        val dataAppBlocksTotalBytes = signed.dataApplication.map(_.blocks.map(_.length.toLong).sum).getOrElse(0L)

        // Binary
        val binaryContentBytes = hashedBinary.content.length.toLong
        val binaryFee = hashedBinary.fee.value.value

        Metrics[F].updateGauge("dag_currency_snapshot_ordinal", signed.ordinal.value) >>
          Metrics[F].updateGauge("dag_currency_snapshot_height", signed.height.value) >>
          Metrics[F].updateGauge("dag_currency_snapshot_signature_count", signed.proofs.size) >>
          // Cumulative counters for value metrics (survive across scrapes unlike gauges)
          Metrics[F].incrementCounterBy("dag_currency_snapshot_blocks_total", signed.blocks.size) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_transactions_total", txCount) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_transaction_amount_cumulative", txAmountTotal) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_transaction_fee_cumulative", txFeeTotal) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_rewards_amount_cumulative", rewardsAmountTotal) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_fee_transactions_amount_cumulative", feeTransactionsAmountTotal) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_allow_spend_amount_cumulative", allowSpendAmountTotal) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_allow_spend_fee_cumulative", allowSpendFeeTotal) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_token_lock_amount_cumulative", tokenLockAmountTotal) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_token_lock_fee_cumulative", tokenLockFeeTotal) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_binary_fee_cumulative", binaryFee) >>
          // Blocks & transactions - counts and values
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_blocks_count", signed.blocks.size) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_transactions_count", txCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_transaction_amount_total", txAmountTotal) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_transaction_fee_total", txFeeTotal) >>
          // Rewards - counts and values
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_rewards_count", rewardsCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_rewards_amount_total", rewardsAmountTotal) >>
          // Tips
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_tips_count", activeTips, Seq(("tip_type", "active"))) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_tips_count", deprecatedTips, Seq(("tip_type", "deprecated"))) >>
          // Extended fields
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_messages_count", messagesCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_global_snapshot_syncs_count", globalSnapshotSyncsCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_artifacts_count", artifactsCount) >>
          // Fee transactions - counts and values
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_fee_transactions_count", feeTransactionsCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_fee_transactions_amount_total", feeTransactionsAmountTotal) >>
          // AllowSpend - counts, amounts, fees
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_allow_spend_blocks_count", allowSpendBlocksCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_allow_spend_tx_count", allowSpendTxCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_allow_spend_amount_total", allowSpendAmountTotal) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_allow_spend_fee_total", allowSpendFeeTotal) >>
          // TokenLock - counts, amounts, fees
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_token_lock_blocks_count", tokenLockBlocksCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_token_lock_tx_count", tokenLockTxCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_token_lock_amount_total", tokenLockAmountTotal) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_token_lock_fee_total", tokenLockFeeTotal) >>
          // Data application sizes
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_data_app_onchain_state_bytes", dataAppOnChainStateBytes) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_data_app_blocks_count", dataAppBlocksCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_data_app_blocks_total_bytes", dataAppBlocksTotalBytes) >>
          // Binary size and fee
          Metrics[F].updateGauge("dag_currency_snapshot_binary_content_bytes", binaryContentBytes) >>
          Metrics[F].updateGauge("dag_currency_snapshot_binary_fee", binaryFee)
      }

      private def notifyDataApplication(signedArtifact: Signed[CurrencySnapshotArtifact]): F[Unit] =
        maybeDataApplication.traverse_ { da =>
          HasherSelector[F].withCurrent(implicit h => signedArtifact.toHashed) >>= da.onSnapshotConsensusResult
        }.handleErrorWith(logger.error(_)("Unhandled exception during onSnapshotConsensusResult"))

      private def checkForkByLastSnapshotHash[A](declarations: SortedMap[PeerId, A], ownHash: Hash, minObservations: Int)(
        implicit extract: A => Hash
      ): F[Unit] =
        recoverIfForking[F](
          ownHash,
          ConsensusStateUpdater.ForkObservation.LastSnapshotHash,
          nodeStorage,
          forkObservationsRef,
          config.forkConfirmationWindow,
          minObservations
        )(
          declarations.map { case (pid, decl) => (pid, extract(decl)) }
        )

      /** Skip facilitatorsHash fork check when transitioning from solo genesis (facilitators=1) to multi-node consensus. PeerQualityTracker
        * penalty state is node-local, causing different facilitatorsHash values on the first multi-node round.
        */
      private def wasLastRoundSolo: F[Boolean] =
        consensusStorage.getLastConsensusOutcome.map {
          case Some(outcome) => outcome.facilitators.value.size <= 1
          case None          => true
        }

      private def checkForkByFacilitatorsHash[A](
        declarations: SortedMap[PeerId, A],
        ownHash: Hash,
        minObservations: Int
      )(extractHash: A => Hash): F[Unit] =
        recoverIfForking[F](
          ownHash,
          ConsensusStateUpdater.ForkObservation.FacilitatorsHash,
          nodeStorage,
          forkObservationsRef,
          config.forkConfirmationWindow,
          minObservations
        )(
          declarations.map { case (pid, decl) => (pid, extractHash(decl)) }
        )

      private def checkForkByConsensusConfigHash(facilities: SortedMap[PeerId, Facility]): F[Unit] = {
        // A `consensusConfigHash` divergence cannot be repaired by recovery
        // download. Surface it via metric + structured log so operators can fix the misconfigured
        // peer; consensus continues but the divergence is visible. See dag-l0 advancer for the full
        // rationale.
        val ownConfigHash = config.deterministicConfigHash
        val peerConfigHashes = facilities.flatMap {
          case (pid, f) => f.consensusConfigHash.map(pid -> _)
        }
        if (peerConfigHashes.nonEmpty)
          logRecoveryUnsuitableMismatch[F](
            ownConfigHash,
            ConsensusStateUpdater.ForkObservation.ConsensusConfigHash
          )(
            SortedMap.from(peerConfigHashes)
          )
        else Applicative[F].unit
      }

      private implicit val extractFacilityHash: Facility => Hash = _.lastSnapshotHash
      private implicit val extractProposalHash: Proposal => Hash = _.lastSnapshotHash
      private implicit val extractSignatureHash: MajoritySignature => Hash = _.lastSnapshotHash
      private implicit val extractBinarySignatureHash: BinarySignature => Hash = _.lastSnapshotHash

      private def clearTimeTriggerIfNeeded(trigger: ConsensusTrigger): F[Unit] =
        Applicative[F].whenA(trigger === TimeTrigger)(consensusStorage.clearTimeTrigger)

      private def recordProposalAffinity(allHashes: List[Hash], ownHash: Hash): F[Unit] =
        Metrics[F].recordDistribution("dag_consensus_proposal_affinity", proposalAffinity(allHashes, ownHash))

      private def logInvalidSignatures(key: CurrencySnapshotKey, total: Int, valid: Int): F[Unit] =
        logger
          .warn(s"Removed ${total - valid} invalid signatures for key=${key.show}, $valid valid remaining")
          .whenA(total != valid)

      private def logInvalidBinarySignatures(key: CurrencySnapshotKey, total: Int, valid: Int): F[Unit] =
        logger
          .warn(s"Removed ${total - valid} invalid binary signatures for key=${key.show}, $valid valid remaining")
          .whenA(total != valid)
    }
}
