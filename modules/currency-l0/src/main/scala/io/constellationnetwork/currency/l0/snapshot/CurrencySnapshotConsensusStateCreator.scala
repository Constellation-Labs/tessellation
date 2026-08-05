package io.constellationnetwork.currency.l0.snapshot

import cats.effect.kernel.Clock
import cats.effect.{Async, Sync}
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.l0.snapshot.schema.{CollectingFacilities, CurrencyConsensusKind, CurrencyConsensusOutcome}
import io.constellationnetwork.currency.schema.CurrencyStateKey
import io.constellationnetwork.currency.schema.currency.CurrencySnapshotContext
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event}
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.Facility
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusPeerDeclaration
import io.constellationnetwork.node.shared.infrastructure.consensus.state.{ConsensusStateCreator, _}
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.selfhealth.LocalHealthMonitor
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

import eu.timepit.refined.auto._
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

  def make[F[_]: Async: Metrics](
    consensusFns: CurrencySnapshotConsensusFunctions[F],
    consensusStorage: CurrencyConsensusStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    gossip: Gossip[F],
    selfId: PeerId,
    seedlist: Option[Set[SeedlistEntry]],
    facilitatorSelector: FacilitatorSelector,
    consensusConfigHash: Hash,
    consensusConfig: ConsensusConfig,
    peerQualityTracker: PeerQualityTracker[F],
    tcaFilter: TrailingCommonAncestorFilter[F],
    eventMempool: EventMempool[F, CurrencySnapshotEvent, CurrencyStateKey],
    localHealthMonitor: LocalHealthMonitor[F],
    // v19 multi-committee Core floor. Mirror of dag-l0 -- per-environment value
    // routed through `SnapshotConfig.coreCommitteeSize`.
    coreCommitteeSize: Int
  ): CurrencySnapshotConsensusStateCreator[F] = new CurrencySnapshotConsensusStateCreator[F] {
    val config: ConsensusConfig = consensusConfig

    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

    def tryFacilitateConsensus(
      key: CurrencySnapshotKey,
      lastOutcome: CurrencyConsensusOutcome,
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind],
      priorAbandonmentCount: Int
    ): F[StateCreateResult] =
      consensusStorage
        .condModifyState(key)(toCreateStateFn(facilitateConsensus(key, lastOutcome, maybeTrigger, resources, priorAbandonmentCount)))
        .flatMap(evalEffect)
        .flatTap(logIfCreated)

    // Reads the stored self-Facility and re-sends via direct push. Mirrors dag-l0.
    def retransmitOwnFacility(key: CurrencySnapshotKey, targets: Set[PeerId]): F[Unit] =
      consensusStorage.getResources(key).flatMap { resources =>
        resources.peerDeclarationsMap
          .get(selfId)
          .flatMap(_.facility)
          .fold(Sync[F].unit) { facility =>
            val declaration = ConsensusPeerDeclaration(key, facility)
            ConsensusLog.info(
              logger,
              Category.Facilitator,
              key.show,
              "n/a",
              Event.FacilityRetransmit,
              "targets" -> targets.size.toString
            ) >>
              gossip.spreadDirect(declaration, targets)
          }
      }

    private def facilitateConsensus(
      key: CurrencySnapshotKey,
      lastOutcome: CurrencyConsensusOutcome,
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind],
      priorAbandonmentCount: Int
    ): F[(CurrencySnapshotConsensusState, F[Unit])] =
      for {
        candidates <- consensusStorage.getCandidates(key.next)
        previousEligible = lastOutcome.eligibleOrFacilitators
        approvedCandidates = lastOutcome.finished.candidates.value
        seedlistPeerIds = seedlist.fold(List.empty[PeerId])(_.toList.map(_.peerId))

        filteredPreviousEligible = previousEligible
          .filter(peerId => seedlistPeerIds.isEmpty || seedlistPeerIds.contains(peerId))

        filteredCandidates = approvedCandidates
          .filter(peerId => seedlistPeerIds.isEmpty || seedlistPeerIds.contains(peerId))

        // Full base. Use only the parent round's canonical facilitator set. In schema v34
        // `finished.candidates` carries the accepted Proposal's open-admission nominee for vote
        // convergence; nomination alone is not membership authority. Only a quorum-certified
        // admission may change this base.
        fullBase = ConsensusPeerController.canonicalFacilitatorBase(
          parentFacilitators = lastOutcome.facilitators.value,
          seedlistPeerIds = seedlistPeerIds
        )

        _ <- logger.debug(
          s"Facilitator selection for key=$key: " +
            s"previousEligible=${filteredPreviousEligible.size}, " +
            s"candidates=${filteredCandidates.size}, " +
            s"fullBase=${fullBase.size}"
        )

        // TCA filter: degraded = consensus-agreed evictions from lastOutcome.removedFacilitators.
        // See dag-l0 mirror for full rationale. Previously this read `signedMajorityArtifact.proofs`
        // which is per-node-local and caused divergent committees across nodes.
        lastFacilitators = lastOutcome.facilitators.value.toSet
        lastSigners = lastFacilitators -- lastOutcome.removedFacilitators.value
        tcaDegraded <- tcaFilter.degradedPeers(lastFacilitators, lastSigners)
        tcaFilteredBase = tcaDegraded match {
          case Some(degraded) =>
            val filtered = fullBase.filterNot(degraded.contains)
            if (filtered.isEmpty) fullBase
            else filtered
          case None => fullBase
        }

        _ <- tcaDegraded.traverse_ { degraded =>
          ConsensusLog.info(
            logger,
            Category.Facilitator,
            key.show,
            "n/a",
            Event.TcaFilterApplied,
            "tcaDegraded" -> degraded.size.toString,
            "fullBase" -> fullBase.size.toString,
            "tcaFiltered" -> tcaFilteredBase.size.toString,
            "degradedPeers" -> degraded.toList.map(_.value.value.take(8)).mkString(",")
          )
        }

        // All eligible after collateral filtering (includes previously removed peers so they can re-enter)
        allEligible <- tcaFilteredBase
          .filterA(
            consensusFns.facilitatorFilter(
              lastOutcome.finished.signedMajorityArtifact,
              lastOutcome.finished.context,
              _
            )
          )
          .map { list =>
            if (list.isEmpty) List(selfId) else list
          }

        filteredOutByCollateral = fullBase.filterNot(allEligible.contains)
        _ <- filteredOutByCollateral.traverse_ { peerId =>
          logger.debug(s"Facilitator ${peerId.show} removed by facilitatorFilter for key=$key")
        }

        // Multi-round removal penalty (security tier). Mirror of dag-l0 cleanup -- v19
        // tier partition handles chronic non-signers, prior-round-missing, tightening
        // window, and candidate deferral at CommitteeBuilder. Penalty remains the
        // post-fork-eviction gate. Deterministic: derived from consensus-agreed lastOutcome.
        // Stage 4 cert-anchored penalties: pure ordinal comparison against the consensus-agreed
        // key, unioned into `penalizedPeers`. Mirror of dag-l0; see there for full rationale.
        certPenalizedPeers = lastOutcome.penaltyUntil
          .getOrElse(SortedMap.empty[PeerId, SnapshotOrdinal])
          .filter { case (_, until) => until.value.value > key.value.value }
          .keySet
        penalizedPeers = lastOutcome.removalPenalties.filter(_._2 > 0).keySet ++ certPenalizedPeers

        // B2 re-admission probation. Non-bypassable; see dag-l0 mirror.
        probationPeers = lastOutcome.readmissionCountdown.filter(_._2 > 0).keySet

        _ <- logger
          .debug(
            s"Removal penalties for key=$key: ${penalizedPeers.size} penalized peers" +
              (if (penalizedPeers.nonEmpty)
                 s" [${lastOutcome.removalPenalties.filter(_._2 > 0).map(kv => s"${kv._1.value.value.take(8)}:${kv._2}").mkString(",")}]"
               else "")
          )
          .whenA(penalizedPeers.nonEmpty)

        _ <- logger
          .debug(
            s"Readmission probation for key=$key: ${probationPeers.size} probation peers" +
              (if (probationPeers.nonEmpty)
                 s" [${lastOutcome.readmissionCountdown.filter(_._2 > 0).map(kv => s"${kv._1.value.value.take(8)}:${kv._2}").mkString(",")}]"
               else "")
          )
          .whenA(probationPeers.nonEmpty)

        // Per-peer quality gauges (Prometheus). With v19 cleanup, the quality-degradation
        // override in CommitteeBuilder demotes peers with cumulative ratio < minRatio to
        // Tier 1, so these gauges remain the key operator signal for "who's drifting toward
        // Tier 1?"
        peerIdLabel = Metrics.unsafeLabelName("peer_id")
        _ <- lastOutcome.peerQuality.toList.traverse_ {
          case (pid, (completed, participated)) =>
            val ratio = if (participated > 0) completed.toDouble / participated.toDouble else 1.0
            val pidTag: Metrics.TagSeq = Seq((peerIdLabel, pid.value.value.take(8)))
            Metrics[F].updateGauge("dag_currency_consensus_peer_quality_ratio", ratio, pidTag) >>
              Metrics[F].updateGauge("dag_currency_consensus_peer_quality_participated", participated.toLong, pidTag) >>
              Metrics[F].updateGauge("dag_currency_consensus_peer_quality_completed", completed.toLong, pidTag)
        }

        // Clear the abandoned-missing tracker every round (local-only, never used for exclusion).
        abandonedMissing <- peerQualityTracker.getAndClearAbandonedMissingPeers

        _ <- ConsensusLog
          .info(
            logger,
            Category.Facilitator,
            key.show,
            "n/a",
            Event.AbandonedMissingLogged,
            "count" -> abandonedMissing.size.toString,
            "peers" -> abandonedMissing.toList.map(_.value.value.take(8)).mkString(",")
          )
          .whenA(abandonedMissing.nonEmpty)

        // Eligibility: penalty + probation only. v19 tier partition at CommitteeBuilder
        // handles all behavioural classification beyond these two security/B2 gates.
        eligibleThisRound = {
          val excluded = penalizedPeers ++ probationPeers
          val filtered = allEligible.filterNot(excluded.contains)
          if (filtered.nonEmpty) filtered
          else {
            val allEligibleMinusProbation = allEligible.filterNot(probationPeers.contains)
            if (allEligibleMinusProbation.nonEmpty) allEligibleMinusProbation
            else if (allEligible.nonEmpty) allEligible
            else List(selfId)
          }
        }

        // Apply deterministic subset selection using hash-distance ordering
        // Uses the previous round's snapshot hash as entropy for randomization
        entropy = lastOutcome.finished.snapshotHash
        selectedFacilitators = facilitatorSelector.select(eligibleThisRound, entropy)
        admissionSizing = ConsensusPeerController.AdmissionSizing.from(
          config,
          coreCommitteeSize,
          selectedFacilitators.size
        )
        expansionIntervalRounds = math.max(1, config.activeAdmissionExpansionIntervalRounds)
        expansionAllowedThisRound = ActiveFacilitatorAdmission.expansionAllowedAtOrdinal(
          key.value.value,
          config.activeAdmissionExpansionIntervalRounds
        )
        maxExpansionThisRound =
          if (expansionAllowedThisRound) config.activeAdmissionMaxExpansionPerRound
          else 0
        // Stage 4 read-side switch with carried-map fallback while the evidence window is
        // empty. Mirror of dag-l0; see there for full rationale. The evidence-vs-carried
        // decision (including empty viewChanges / selfHealth in the evidence regime) lives
        // entirely inside controllerInputsWithFallback -- no conditional logic here.
        controllerInputs = ControllerEvidenceDerivation.controllerInputsWithFallback(
          evidence = lastOutcome.controllerEvidence.getOrElse(SortedMap.empty),
          carriedScores = lastOutcome.activeAdmissionScores.toMap,
          carriedQuality = lastOutcome.peerQuality.toMap,
          carriedTiers = lastOutcome.peerTiers,
          carriedViewChanges = lastOutcome.peerViewChanges.toMap,
          carriedSelfHealth = lastOutcome.peerSelfHealth.toMap
        )
        _ <- logger.info(
          s"Controller inputs for key=$key: " + (
            if (controllerInputs.evidenceRounds === 0) "controller_evidence=empty fallback=carried"
            else s"controller_evidence=${controllerInputs.evidenceRounds} rounds"
          )
        )
        activeAdmission = ConsensusPeerController.chooseActive(
          ConsensusPeerController.AdmissionInput(
            selected = selectedFacilitators,
            recentSigners = lastOutcome.recentSigners,
            latestRoundStartFacilitators =
              lastOutcome.controllerEvidence.flatMap(_.lastOption.map(_._2.roundStartFacilitators.toSet)).getOrElse(Set.empty),
            peerQuality = controllerInputs.peerQuality,
            activeScores = controllerInputs.activeScores,
            sizing = admissionSizing,
            minParticipationObservations = config.minParticipationObservations,
            minParticipationRatio = config.minParticipationRatio,
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
              maxExpansionPerRound = maxExpansionThisRound,
              minProbationReentrySlots = config.activeAdmissionMinProbationReentrySlots,
              recentSignerWindow = config.activeAdmissionRecentSignerWindow
            )
          )
        )
        activeFacilitators = activeAdmission.active

        _ <- ConsensusLog
          .info(
            logger,
            Category.Facilitator,
            key.show,
            "n/a",
            Event.FacilitatorSubsetting,
            "allEligible" -> allEligible.size.toString,
            "eligibleThisRound" -> eligibleThisRound.size.toString,
            "selected" -> selectedFacilitators.size.toString,
            "active" -> activeFacilitators.size.toString,
            "activeTarget" -> activeAdmission.targetSize.toString,
            "activeCandidates" -> activeAdmission.candidateSize.toString,
            "promotedCandidates" -> activeAdmission.promotedCandidateSize.toString,
            "scoreExcluded" -> activeAdmission.scoreExcludedSize.toString,
            "qualityExcluded" -> activeAdmission.qualityExcludedSize.toString,
            "demotedRecentSigners" -> activeAdmission.demotedRecentSignerSize.toString,
            "belowRetainRecentSigners" -> activeAdmission.belowRetainRecentSignerSize.toString,
            "recentSignerPool" -> activeAdmission.recentSignerPoolSize.toString,
            "expansionAdmitted" -> activeAdmission.expansionAdmittedSize.toString,
            "reserveAdmitted" -> activeAdmission.reserveAdmittedSize.toString,
            "probationAdmitted" -> activeAdmission.probationAdmittedSize.toString,
            "stickyProbationCandidates" -> activeAdmission.stickyProbationCandidateSize.toString,
            "freshProbationCandidates" -> activeAdmission.freshProbationCandidateSize.toString,
            "freshProbationStarved" -> activeAdmission.freshProbationStarved.toString,
            "expansionIntervalRounds" -> expansionIntervalRounds.toString,
            "expansionAllowed" -> expansionAllowedThisRound.toString,
            "recentSignerWindow" -> activeAdmission.recentWindowSize.toString,
            "recentSignerFilterApplied" -> activeAdmission.recentFilterApplied.toString,
            "recentSignerExclusions" -> activeAdmission.exclusions.size.toString
          )
          .whenA(selectedFacilitators.size < allEligible.size || activeAdmission.exclusions.nonEmpty)

        admissionReasonLabel = Metrics.unsafeLabelName("reason")
        admissionDecisionLabel = Metrics.unsafeLabelName("decision")
        _ <- activeAdmission.exclusions
          .groupBy(_.reason.label)
          .toList
          .traverse_ {
            case (reason, exclusions) =>
              Metrics[F].incrementCounterBy(
                "dag_consensus_active_facilitator_admission_total",
                exclusions.size,
                Seq(
                  admissionDecisionLabel -> "excluded",
                  admissionReasonLabel -> reason
                )
              )
          }
        _ <- Metrics[F].incrementCounterBy(
          "dag_consensus_active_facilitator_expansion_admitted_total",
          activeAdmission.expansionAdmittedSize,
          Seq.empty
        )
        _ <- Metrics[F].incrementCounterBy(
          "dag_consensus_active_facilitator_reserve_admitted_total",
          activeAdmission.reserveAdmittedSize,
          Seq.empty
        )
        _ <- Metrics[F].incrementCounterBy(
          "dag_consensus_active_facilitator_admission_total",
          activeAdmission.probationAdmittedSize,
          Seq(
            admissionDecisionLabel -> "admitted",
            admissionReasonLabel -> "probation"
          )
        )
        _ <- activeAdmission.exclusions
          .groupBy(_.reason.label)
          .toList
          .traverse_ {
            case (reason, exclusions) =>
              Metrics[F].incrementCounterBy(
                "dag_consensus_active_facilitator_expansion_excluded_total",
                exclusions.size,
                Seq(admissionReasonLabel -> reason)
              )
          }
        _ <- Metrics[F].incrementCounterBy(
          "dag_consensus_active_facilitator_admission_total",
          activeFacilitators.size,
          Seq(
            admissionDecisionLabel -> "admitted",
            admissionReasonLabel -> "selected_pool"
          )
        )
        _ <- Metrics[F].updateGauge("dag_consensus_active_facilitator_selected_size", selectedFacilitators.size.toLong)
        _ <- Metrics[F].updateGauge("dag_consensus_active_facilitator_target_size", activeAdmission.targetSize.toLong)
        _ <- Metrics[F].updateGauge("dag_consensus_active_facilitator_candidate_size", activeAdmission.candidateSize.toLong)
        _ <- Metrics[F].updateGauge(
          "dag_consensus_active_facilitator_promoted_candidate_size",
          activeAdmission.promotedCandidateSize.toLong
        )
        _ <- Metrics[F].updateGauge("dag_consensus_active_facilitator_admitted_size", activeFacilitators.size.toLong)
        _ <- Metrics[F]
          .updateGauge("dag_consensus_active_facilitator_probation_admitted_size", activeAdmission.probationAdmittedSize.toLong)
        _ <- Metrics[F].updateGauge(
          "dag_consensus_active_facilitator_sticky_probation_candidate_size",
          activeAdmission.stickyProbationCandidateSize.toLong
        )
        _ <- Metrics[F].updateGauge(
          "dag_consensus_active_facilitator_fresh_probation_candidate_size",
          activeAdmission.freshProbationCandidateSize.toLong
        )
        _ <- Metrics[F].updateGauge(
          "dag_consensus_active_facilitator_fresh_probation_starved",
          if (activeAdmission.freshProbationStarved) 1L else 0L
        )
        _ <- Metrics[F]
          .updateGauge("dag_consensus_active_facilitator_reserve_admitted_size", activeAdmission.reserveAdmittedSize.toLong)
        _ <- Metrics[F].updateGauge("dag_consensus_active_facilitator_recent_pool_size", activeAdmission.recentSignerPoolSize.toLong)
        activeExclusionCounts = activeAdmission.exclusions.groupMapReduce(_.reason.label)(_ => 1)(_ + _)
        _ <- List(
          ActiveFacilitatorAdmission.ExclusionReason.QualityBelowThreshold,
          ActiveFacilitatorAdmission.ExclusionReason.ScoreBelowPromoteThreshold,
          ActiveFacilitatorAdmission.ExclusionReason.ScoreBelowDemoteThreshold,
          ActiveFacilitatorAdmission.ExclusionReason.ScoreBelowRetainThreshold,
          ActiveFacilitatorAdmission.ExclusionReason.MissedLatestRound
        ).traverse_ { reason =>
          Metrics[F].updateGauge(
            "dag_consensus_active_facilitator_blocker_size",
            activeExclusionCounts.getOrElse(reason.label, 0).toLong,
            Seq(admissionReasonLabel -> reason.label)
          )
        }

        (withdrawn, active) = activeFacilitators.partition { peerId =>
          resources.withdrawalsMap.get(peerId).contains(CurrencyConsensusKind.Facility)
        }
        _ <- withdrawn.traverse_ { peerId =>
          logger.info(s"Facilitator ${peerId.show} has withdrawn from consensus at key=$key")
        }
        time <- Clock[F].monotonic
        lastGlobalSnapshotOrdinal <- lastGlobalSnapshotStorage.getOrdinal.map(_.getOrElse(SnapshotOrdinal.MinValue))

        // Build Facility once, self-store locally (no reliance on gossip self-loopback), then
        // direct-push to the active facilitator set. Matches the dag-l0 creator -- see rationale there.
        effect = for {
          eventHashes <- eventMempool.getEventHashes
          // v15: see GlobalSnapshotConsensusStateCreator for full rationale -- the hint is
          // captured at effect run time so the most recent LocalHealthMonitor sample rides
          // with the outgoing Facility.
          selfHealth <- localHealthMonitor.current
          // v19 phase 2: wall-clock millis at signing time. See dag-l0 mirror.
          proposerClockMs <- Clock[F].realTime.map(_.toMillis)
          facility = Facility(
            eventHashes,
            candidates,
            maybeTrigger,
            lastOutcome.finished.facilitatorsHash,
            lastGlobalSnapshotOrdinal,
            lastOutcome.finished.snapshotHash,
            consensusConfigHash = consensusConfigHash.some,
            selfHealthHint = selfHealth.some,
            proposerClockMs = proposerClockMs.some
          )
          declaration = ConsensusPeerDeclaration(key, facility)
          _ <- consensusStorage.addFacility(selfId, key, facility)
          _ <- gossip.spreadDirect(declaration, active.toSet)
        } yield ()

        // v19 multi-committee derivation, including the chronic-core replacement ladder
        // (evidence-derived `chronicMisses` bars chronic peers from Core and swaps them for
        // healthy reserves). Mirror of dag-l0; see CommitteeBuilder scaladoc for the ladder.
        committees = CommitteeBuilder.build(
          candidates = active,
          priorTiers = controllerInputs.peerTiers,
          peerQuality = controllerInputs.peerQuality,
          coreFloor = coreCommitteeSize,
          minObservations = config.minParticipationObservations,
          minRatio = config.minParticipationRatio,
          nonCorePeers = activeAdmission.probationAdmitted.toSet,
          chronicMisses = controllerInputs.chronicMisses,
          activeScores = controllerInputs.activeScores
        )

        _ <- logger
          .info(
            s"Chronic core replacement at key=$key: " +
              s"excluded=${committees.chronicExcluded.map { case (pid, misses) => s"${pid.value.value.take(8)}:$misses" }.mkString(",")} " +
              s"replacements=${committees.chronicReplacements.map(_.value.value.take(8)).mkString(",")} " +
              s"readmitted=${committees.chronicReadmitted.map { case (pid, misses) => s"${pid.value.value.take(8)}:$misses" }.mkString(",")}"
          )
          .whenA(committees.chronicExcluded.nonEmpty || committees.chronicReadmitted.nonEmpty)
        _ <- Metrics[F].incrementCounterBy(
          "dag_consensus_chronic_core_replacement_total",
          committees.chronicReplacements.size,
          Seq.empty
        )
        _ <- Metrics[F].updateGauge("dag_consensus_chronic_core_excluded_size", committees.chronicExcluded.size.toLong)

        _ <- ConsensusLog.info(
          logger,
          Category.Facilitator,
          key.show,
          "n/a",
          Event.FacilitatorsFinalized,
          "core" -> committees.core.size.toString,
          "tier1" -> committees.tier1.size.toString,
          "witness" -> committees.witness.size.toString,
          "coreFloor" -> coreCommitteeSize.toString
        )

        // Quality-weighted leader selection using consensus-agreed integer quality scores.
        // v19: leader pool draws ONLY from the Core committee. See dag-l0 mirror.
        coreList = committees.core
        leaderEligibility = LeaderEligibility.fromRecentSigners(
          core = coreList,
          peerQuality = controllerInputs.peerQuality,
          recentSigners = lastOutcome.recentSigners,
          minParticipationObservations = config.minParticipationObservations,
          minLeaderPoolSize = config.minLeaderPoolSize
        )
        leaderPool = leaderEligibility.leaderPool
        exclusionReasonLabel = Metrics.unsafeLabelName("reason")
        exclusionDecisionLabel = Metrics.unsafeLabelName("decision")
        _ <- leaderEligibility.exclusions
          .groupBy(_.reason.label)
          .toList
          .traverse_ {
            case (reason, exclusions) =>
              Metrics[F].incrementCounterBy(
                "dag_consensus_leader_eligibility_total",
                exclusions.size,
                Seq(
                  exclusionDecisionLabel -> "excluded",
                  exclusionReasonLabel -> reason
                )
              )
          }
        _ <- Metrics[F].incrementCounterBy(
          "dag_consensus_leader_eligibility_total",
          leaderPool.size,
          Seq(
            exclusionDecisionLabel -> "eligible",
            exclusionReasonLabel -> "selected_pool"
          )
        )
        // Deterministic currency-L0 view seed (mirror of dag-l0 GlobalSnapshotConsensusStateCreator):
        // `timeView` is computed as a pacemaker timeout hint only, NOT proposal-critical state. A local
        // wall clock must not pick the round-start view/leader directly; view movement must arrive
        // through the signed VCV/VCC path so all honest peers converge on the same view before
        // accepting proposals.
        nowMs <- Clock[F].realTime.map(_.toMillis)
        parentEndTimeMs = lastOutcome.recentRoundEndTimes.lastOption.map(_._2)
        timeView = ViewFromTime.compute(nowMs, parentEndTimeMs, config.viewInterval.toMillis)
        // Round-start view must be certificate-derived. `priorAbandonmentCount` is a local retry
        // diagnostic and `timeView` is only a pacemaker hint; neither is quorum evidence, so neither
        // may seed proposal-critical view/leader selection.
        initialView = 0
        leader = facilitatorSelector.selectLeaderWeighted(
          leaderPool,
          entropy,
          viewNumber = initialView,
          qualityScores = controllerInputs.peerQuality,
          selfHealthHints = controllerInputs.selfHealth,
          peerViewChanges = controllerInputs.viewChanges,
          minLeaderRatioPct = config.leaderRotationMinRatioPct,
          hardLeaderQualityScorePct = config.hardLeaderQualityScorePct,
          minLeaderPoolSize = config.minLeaderPoolSize
        )

        _ <- ConsensusLog.info(
          logger,
          Category.Facilitator,
          key.show,
          if (leader === selfId) "Leader" else "Validator",
          Event.FacilitatorsFinalized,
          "eligible" -> allEligible.size.toString,
          "active" -> active.size.toString,
          "core" -> coreList.size.toString,
          "leaderPool" -> leaderPool.size.toString,
          "recentSignerActivePool" -> activeAdmission.recentSignerPoolSize.toString,
          "recentSignerActiveFilterApplied" -> activeAdmission.recentFilterApplied.toString,
          "activeExclusions" -> activeAdmission.exclusions.size.toString,
          "graduatedLeaderPool" -> leaderEligibility.graduatedPoolSize.toString,
          "recentSignerLeaderPool" -> leaderEligibility.recentSignerPoolSize.toString,
          "recentSignerWindow" -> leaderEligibility.recentWindowSize.toString,
          "recentSignerFilterApplied" -> leaderEligibility.recentFilterApplied.toString,
          "leaderExclusions" -> leaderEligibility.exclusions.size.toString,
          "excluded" -> (allEligible.size - eligibleThisRound.size).toString,
          "leader" -> ConsensusLog.pid(leader),
          "timeViewTimeoutHint" -> timeView.toString
        )

        state = ConsensusState[CurrencySnapshotKey, CurrencySnapshotStatus, CurrencyConsensusOutcome, CurrencyConsensusKind](
          key,
          lastOutcome,
          Facilitators(active),
          // Canonical round-start committee -- frozen at creation, never mutated by withdrawals.
          Facilitators(active),
          CollectingFacilities(
            maybeTrigger,
            lastOutcome.finished.facilitatorsHash,
            lastOutcome.finished.snapshotHash
          ),
          time,
          withdrawnFacilitators = WithdrawnFacilitators(withdrawn.toSet),
          eligibleFacilitators = EligibleFacilitators(allEligible),
          coreFacilitators = CoreFacilitators(committees.core),
          tier1Facilitators = Tier1Facilitators(committees.tier1),
          leader = leader,
          // Round-start view = 0 (certificate-derived only; mirror of dag-l0). MUST match the
          // viewNumber argument passed to selectLeaderWeighted above for leader consistency.
          viewNumber = initialView,
          // Mirror dag-l0 alpha.90 P0 #1 self-wedge fix -- see GlobalSnapshotConsensusStateCreator
          // for the full rationale on `initialViewNumber`. Frozen at construction so the validator
          // can accept the no-VCC seed-view proposal.
          initialViewNumber = initialView,
          entropy = entropy
        )

        role = ConsensusLog.role(selfId, leader)
        leaderScore <- peerQualityTracker.getQualityScore(leader)
        _ <- {
          val basePairs = Seq(
            "trigger" -> maybeTrigger.map(_.toString).getOrElse("none"),
            "facilitators" -> active.size.toString,
            "eligible" -> allEligible.size.toString,
            "candidates" -> filteredCandidates.size.toString,
            "leader" -> ConsensusLog.pid(leader),
            "leaderScore" -> f"$leaderScore%.2f",
            "self" -> ConsensusLog.pid(selfId),
            "view" -> initialView.toString,
            "lastGlobalOrd" -> lastGlobalSnapshotOrdinal.show
          )
          val optionalPairs =
            (if (withdrawn.nonEmpty) Seq("withdrawn" -> withdrawn.size.toString) else Seq.empty) ++
              (if (penalizedPeers.nonEmpty) Seq("penalized" -> penalizedPeers.size.toString) else Seq.empty) ++
              (if (probationPeers.nonEmpty) Seq("probation" -> probationPeers.size.toString) else Seq.empty) ++
              (if (abandonedMissing.nonEmpty) Seq("abandonedMissing" -> abandonedMissing.size.toString) else Seq.empty) ++
              (if (priorAbandonmentCount > 0) Seq("retryCount" -> priorAbandonmentCount.toString) else Seq.empty)
          ConsensusLog.info(logger, Category.Lifecycle, key.show, role, Event.RoundStarted, (basePairs ++ optionalPairs): _*)
        }

      } yield (state, effect)
  }
}
