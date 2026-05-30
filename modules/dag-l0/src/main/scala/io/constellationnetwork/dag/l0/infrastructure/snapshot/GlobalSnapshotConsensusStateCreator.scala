package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.effect.Async
import cats.effect.kernel.{Clock, Sync}
import cats.syntax.all._

import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.GlobalSnapshotEvent
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.{CollectingFacilities, GlobalConsensusKind, GlobalConsensusOutcome}
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.Category._
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.Event._
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.Facility
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusPeerDeclaration
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.selfhealth.LocalHealthMonitor
import io.constellationnetwork.schema.mpt.GlobalStateKey
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

import eu.timepit.refined.auto._
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
  def make[F[_]: Async: Metrics](
    consensusFns: GlobalSnapshotConsensusFunctions[F],
    consensusStorage: GlobalConsensusStorage[F],
    gossip: Gossip[F],
    selfId: PeerId,
    seedlist: Option[Set[SeedlistEntry]],
    facilitatorSelector: FacilitatorSelector,
    consensusConfigHash: Hash,
    consensusConfig: ConsensusConfig,
    peerQualityTracker: PeerQualityTracker[F],
    tcaFilter: TrailingCommonAncestorFilter[F],
    eventMempool: EventMempool[F, GlobalSnapshotEvent, GlobalStateKey],
    localHealthMonitor: LocalHealthMonitor[F],
    // v19 multi-committee floor for the Core committee. The Core committee is the
    // active LIVENESS quorum -- quorum threshold is computed against
    // `coreFacilitators.value.size`, NOT the full round-start committee. If Tier 2
    // (carried-forward) peers fall below this floor, CommitteeBuilder deterministically
    // promotes Tier 1 peers (lexicographic-sorted by PeerId hex) until the floor is met.
    coreCommitteeSize: Int
  ): GlobalSnapshotConsensusStateCreator[F] = new GlobalSnapshotConsensusStateCreator[F] {
    val config: ConsensusConfig = consensusConfig

    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

    def tryFacilitateConsensus(
      key: GlobalSnapshotKey,
      lastOutcome: GlobalConsensusOutcome,
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind],
      priorAbandonmentCount: Int
    ): F[StateCreateResult] =
      consensusStorage
        .condModifyState(key)(toCreateStateFn(facilitateConsensus(key, lastOutcome, maybeTrigger, resources, priorAbandonmentCount)))
        .flatMap(evalEffect)
        .flatTap(logIfCreated)

    // Reads the stored self-Facility (written at round creation by the effect above) and retransmits
    // it via the same direct-push path. Returns F.unit if no stored declaration exists, which happens
    // either pre-creation or after cleanup.
    def retransmitOwnFacility(key: GlobalSnapshotKey, targets: Set[PeerId]): F[Unit] =
      consensusStorage.getResources(key).flatMap { resources =>
        resources.peerDeclarationsMap
          .get(selfId)
          .flatMap(_.facility)
          .fold(Sync[F].unit) { facility =>
            val declaration = ConsensusPeerDeclaration(key, facility)
            ConsensusLog.info(
              logger,
              Facilitator,
              key.show,
              "n/a",
              FacilityRetransmit,
              "targets" -> targets.size.toString
            ) >>
              gossip.spreadDirect(declaration, targets)
          }
      }

    private def facilitateConsensus(
      key: GlobalSnapshotKey,
      lastOutcome: GlobalConsensusOutcome,
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind],
      priorAbandonmentCount: Int
    ): F[(GlobalSnapshotConsensusState, F[Unit])] =
      for {
        candidates <- consensusStorage.getCandidates(key.next)
        previousEligible = lastOutcome.eligibleOrFacilitators
        approvedCandidates = lastOutcome.finished.candidates.value
        seedlistPeerIds = seedlist.map(_.map(_.peerId)).getOrElse(Set.empty)

        filteredPreviousEligible = previousEligible
          .filter(peerId => seedlist.isEmpty || seedlistPeerIds.contains(peerId))

        filteredCandidates = approvedCandidates
          .filter(peerId => seedlist.isEmpty || seedlistPeerIds.contains(peerId))

        previousEligibleSet = filteredPreviousEligible.toSet

        // Full base. Removed peers stay in this set so they can re-enter in future rounds;
        // the multi-round penalty filter (penalizedPeers below) is the only behavioural gate
        // that suppresses them. selfId is NOT unconditionally added: each node adding its own
        // selfId creates a divergent facilitator set per node, causing fork detection
        // (facilitatorsHash mismatch) and permanent divergence. Instead, nodes join via the
        // candidate registration mechanism (filteredCandidates above). Genesis ordinal 1 (empty
        // previousEligible + empty candidates) is handled by the allEligible fallback below:
        // `if (list.isEmpty) List(selfId)`.
        fullBase = (filteredPreviousEligible ++ filteredCandidates).distinct

        _ <- logger.debug(
          s"Facilitator selection for key=$key: " +
            s"previousEligible=${filteredPreviousEligible.size}, " +
            s"candidates=${filteredCandidates.size}, " +
            s"fullBase=${fullBase.size}"
        )

        // TCA (Trailing Common Ancestor): exclude degraded peers. Degraded = peers who were
        // facilitators in the previous round but got evicted via the consensus-agreed facility-phase
        // fork-eviction (stored in `state.removedFacilitators`). Previously this compared against
        // `signedMajorityArtifact.proofs` (who actually signed), but THAT set is per-node-local:
        // each node's signed snapshot carries only the proofs it collected before CASing. Fast
        // finalizers stop at quorum; slower finalizers see more. Using it here caused different
        // nodes to derive different degraded sets → different committees → cascading divergence.
        //
        // Now we derive degraded purely from consensus-agreed state: `lastFacilitators -
        // removedFacilitators`. A peer that participated and wasn't fork-evicted is "presumed to
        // have signed" for TCA purposes, matching the Phase 3 canonical-signers philosophy.
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
            Facilitator,
            key.show,
            "n/a",
            TcaFilterApplied,
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

        // Multi-round removal penalty (the security tier of the eligibility pipeline). Peers
        // evicted via consensus-witnessed fork-eviction stay excluded for `removalPenaltyRounds`
        // rounds before they can re-enter at all. v19 cleanup: this is the ONLY behavioural
        // filter remaining; chronic-non-signer / prior-round-missing / tightening-window /
        // candidate-deferral filters were retired in favour of CommitteeBuilder's tier-aware
        // partition (peers with degraded peerQuality land in Tier 1, still sign, no longer
        // gate the liveness quorum). Deterministic: derived from consensus-agreed lastOutcome.
        penalizedPeers = lastOutcome.removalPenalties.filter(_._2 > 0).keySet

        // B2 re-admission probation: peers whose `removalPenalty` just expired sit in
        // `readmissionCountdown` for `readmissionProbationRounds` before they can re-enter
        // the committee. Excluded from the round NON-BYPASSABLY: re-admission requires a
        // consensus-witnessed AdmissionCertificate embedded in a Proposal (cleared at
        // round-finish in the advancer). Deterministic: derived from consensus-agreed lastOutcome.
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
        // override in CommitteeBuilder demotes peers with cumulative ratio < minRatio to Tier 1,
        // so these gauges remain the key operator signal for "who's drifting toward Tier 1?"
        peerIdLabel = Metrics.unsafeLabelName("peer_id")
        _ <- lastOutcome.peerQuality.toList.traverse_ {
          case (pid, (completed, participated)) =>
            val ratio = if (participated > 0) completed.toDouble / participated.toDouble else 1.0
            val pidTag: Metrics.TagSeq = Seq((peerIdLabel, pid.value.value.take(8)))
            Metrics[F].updateGauge("dag_consensus_peer_quality_ratio", ratio, pidTag) >>
              Metrics[F].updateGauge("dag_consensus_peer_quality_participated", participated.toLong, pidTag) >>
              Metrics[F].updateGauge("dag_consensus_peer_quality_completed", completed.toLong, pidTag)
        }

        // Clear the abandoned-missing tracker every round (local-only state, never used for
        // exclusion -- the deterministic mechanisms above cover unresponsive peers).
        abandonedMissing <- peerQualityTracker.getAndClearAbandonedMissingPeers

        _ <- ConsensusLog
          .info(
            logger,
            Facilitator,
            key.show,
            "n/a",
            AbandonedMissingLogged,
            "count" -> abandonedMissing.size.toString,
            "peers" -> abandonedMissing.toList.map(_.value.value.take(8)).mkString(",")
          )
          .whenA(abandonedMissing.nonEmpty)

        // Eligibility: penalty + probation are the only behavioural gates. Everything else
        // that was previously filtered out (chronic non-signers, prior-round-missing,
        // tightening-window exclusions, new-peer deferrals) is now handled at CommitteeBuilder
        // by partitioning into Core / Tier 1 / Witness rather than excluding -- Tier 1 peers
        // sign and earn but do not count toward the liveness quorum, so chronic peers cannot
        // wedge consensus by being absent. Fallback ladder preserves probation as non-bypassable.
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
        activeFacilitators = facilitatorSelector.select(eligibleThisRound, entropy)

        _ <- ConsensusLog
          .info(
            logger,
            Facilitator,
            key.show,
            "n/a",
            FacilitatorSubsetting,
            "allEligible" -> allEligible.size.toString,
            "eligibleThisRound" -> eligibleThisRound.size.toString,
            "selected" -> activeFacilitators.size.toString
          )
          .whenA(activeFacilitators.size < allEligible.size)

        (withdrawn, active) = activeFacilitators.partition { peerId =>
          resources.withdrawalsMap.get(peerId).contains(GlobalConsensusKind.Facility)
        }

        _ <- withdrawn.traverse_ { peerId =>
          logger.info(s"Facilitator ${peerId.show} has withdrawn from consensus at key=$key")
        }

        time <- Clock[F].monotonic

        // Build Facility once, then:
        //   1. Store locally so self-facility is present without depending on gossip self-loopback.
        //   2. Direct-push to the active facilitator set (same delivery class as Proposal / Signature)
        //      so peers receive it through the reliable path, not the best-effort broadcast.
        // `eventHashes` is captured at effect run time (same as before) to reflect the current mempool.
        // v15: `selfHealthHint` is also captured at effect run time so the most recently-derived
        // hint (Healthy/Degraded/Critical) rides on the outgoing Facility -- the leader aggregates
        // these across the committee into `Proposal.observedSelfHealth`.
        effect = for {
          eventHashes <- eventMempool.getEventHashes
          selfHealth <- localHealthMonitor.current
          // v19 phase 2: wall-clock millis at signing time. Raw, no bucketing; the
          // round-finalize median absorbs outliers and the consume-site clamp pins
          // monotonicity against the parent. See docs/consensus/view-from-time-anchor.md.
          proposerClockMs <- Clock[F].realTime.map(_.toMillis)
          facility = Facility(
            eventHashes,
            candidates,
            maybeTrigger,
            lastOutcome.finished.facilitatorsHash,
            lastOutcome.key,
            lastOutcome.finished.snapshotHash,
            consensusConfigHash = consensusConfigHash.some,
            selfHealthHint = selfHealth.some,
            proposerClockMs = proposerClockMs.some
          )
          declaration = ConsensusPeerDeclaration(key, facility)
          _ <- consensusStorage.addFacility(selfId, key, facility)
          _ <- gossip.spreadDirect(declaration, active.toSet)
        } yield ()

        // v19 multi-committee derivation. Partition `active` into Core / Tier 1 / Witness
        // using the carried-forward `lastOutcome.peerTiers` plus the consensus-agreed
        // `lastOutcome.peerQuality` history. Tier assignment rule (re-derived every round):
        //   1. Quality-degradation override -- any peer with observed `participated >=
        //      minParticipationObservations` AND `completed/participated < minParticipationRatio`
        //      is forced to Tier 1, even if `priorTiers` says Core. Structural protection so a
        //      chronic-but-classified peer cannot gate liveness; recovers on its own next round
        //      if the ratio climbs back above the bar.
        //   2. Carried-forward classification from `priorTiers`.
        //   3. Quality-proven bootstrap -- new joiners (absent from `priorTiers`) land in Core
        //      iff their `peerQuality` shows them above the ratio bar with enough observations.
        //   4. Default Tier 1 -- new peers without proven participation join the witness pool,
        //      NOT the liveness quorum. Replaces the original "everyone defaults to Core"
        //      bootstrap that let chronic-but-unclassified peers wedge the cluster.
        // Core-floor promotion then tops Core up to `coreCommitteeSize` by ranking the Tier 1
        // pool on `peerQuality` (descending ratio, descending completed, then PeerId lex).
        // At genesis (empty `peerQuality`) ranking collapses to pure lex order so the cluster
        // bootstraps deterministically from scratch.
        committees = CommitteeBuilder.build(
          candidates = active,
          priorTiers = lastOutcome.peerTiers,
          peerQuality = lastOutcome.peerQuality,
          coreFloor = coreCommitteeSize,
          minObservations = config.minParticipationObservations,
          minRatio = config.minParticipationRatio
        )

        _ <- ConsensusLog.info(
          logger,
          Facilitator,
          key.show,
          "n/a",
          FacilitatorsFinalized,
          "core" -> committees.core.size.toString,
          "tier1" -> committees.tier1.size.toString,
          "witness" -> committees.witness.size.toString,
          "coreFloor" -> coreCommitteeSize.toString
        )
        _ <- Metrics[F].updateGauge("dag_consensus_committee_core_size", committees.core.size.toLong)
        _ <- Metrics[F].updateGauge(
          "dag_consensus_committee_tier_size",
          committees.core.size.toLong,
          Seq(Metrics.unsafeLabelName("tier") -> "core")
        )
        _ <- Metrics[F].updateGauge(
          "dag_consensus_committee_tier_size",
          committees.tier1.size.toLong,
          Seq(Metrics.unsafeLabelName("tier") -> "tier1")
        )
        _ <- Metrics[F].updateGauge(
          "dag_consensus_committee_tier_size",
          committees.witness.size.toLong,
          Seq(Metrics.unsafeLabelName("tier") -> "witness")
        )
        _ <- Metrics[F].updateGauge("dag_consensus_committee_core_floor", coreCommitteeSize.toLong)

        // Quality-weighted leader selection: use consensus-agreed quality scores
        // so all nodes compute the same leader deterministically.
        // Pass raw (completed, participated) integers -- the selector uses integer-only
        // tier computation (tier = participated - completed = failure count) to avoid
        // platform-dependent float-to-long conversion differences.
        //
        // v19: leader selection draws ONLY from the Core committee. Tier 1 and Witness
        // peers are not eligible to lead -- they observe and witness, but the LIVENESS
        // quorum is gated on Core. This makes Core both the quorum denominator and the
        // leader pool, so a peer that loses its Core seat also loses its ability to lead.
        //
        // Graduation filter (unchanged from v18): restrict the leader pool to peers with
        // `participated >= minParticipationObservations` AND `completed >= 1` in the
        // consensus-agreed peerQuality outcome. The graduated pool must contain at least
        // 2 peers for view rotation to be meaningful -- with a single peer,
        // `viewNumber % 1 = 0` always returns the same peer and view change becomes a no-op.
        // At genesis / cold start, OR in a solo-bootstrap tail (only one peer graduated),
        // fall back to the full Core committee.
        coreList = committees.core
        graduatedLeaderPool = coreList.filter { pid =>
          val (completed, participated) = lastOutcome.peerQuality.getOrElse(pid, (0, 0))
          participated >= config.minParticipationObservations && completed >= 1
        }
        leaderPool = if (graduatedLeaderPool.size >= 2) graduatedLeaderPool else coreList
        // Deterministic GL0 view seed:
        // `timeView` remains computed from the timestamp window, but it is treated as a pacemaker
        // timeout hint, not as unilateral proposal-critical state. A local wall clock must not pick
        // the proposal view/leader directly; view movement needs to arrive through the signed VCV/VCC
        // path so all honest peers converge on the same view before accepting proposals.
        nowMs <- Clock[F].realTime.map(_.toMillis)
        parentEndTimeMs = lastOutcome.recentRoundEndTimes.lastOption.map(_._2)
        timeView = ViewFromTime.compute(nowMs, parentEndTimeMs, config.viewInterval.toMillis)
        // Round-start view must be certificate-derived. `priorAbandonmentCount` is a local retry
        // diagnostic, and `timeView` is only a pacemaker hint for signed VCV emission. Neither is
        // quorum evidence, so neither may directly seed proposal-critical view/leader selection.
        initialView = 0
        leader = facilitatorSelector.selectLeaderWeighted(
          leaderPool,
          entropy,
          viewNumber = initialView,
          qualityScores = lastOutcome.peerQuality,
          selfHealthHints = lastOutcome.peerSelfHealth,
          peerViewChanges = lastOutcome.peerViewChanges.toMap,
          minLeaderRatioPct = config.leaderRotationMinRatioPct,
          hardLeaderQualityScorePct = config.hardLeaderQualityScorePct,
          minLeaderPoolSize = config.minLeaderPoolSize
        )

        _ <- ConsensusLog.info(
          logger,
          Facilitator,
          key.show,
          if (leader === selfId) "Leader" else "Validator",
          FacilitatorsFinalized,
          "eligible" -> allEligible.size.toString,
          "active" -> active.size.toString,
          "core" -> coreList.size.toString,
          "excluded" -> (allEligible.size - eligibleThisRound.size).toString,
          "leader" -> ConsensusLog.pid(leader)
        )

        state = ConsensusState[GlobalSnapshotKey, GlobalSnapshotStatus, GlobalConsensusOutcome, GlobalConsensusKind](
          key,
          lastOutcome,
          Facilitators(active),
          // Canonical round-start committee -- same set as `facilitators` at creation,
          // but frozen for the lifetime of the round even when peers withdraw.
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
          // Round-start view = certified initial view. MUST match the
          // `viewNumber = initialView` argument passed to selectLeaderWeighted above so the
          // leader the round believes it has at view=N matches the leader the selector returns
          // at view=N. View-change continues monotonically from certified VCC advancement.
          viewNumber = initialView,
          // Frozen round-start view for the alpha.90 P0 #1 self-wedge fix. VCC-driven advances
          // (StateTransitions.scala `s.copy(viewNumber = toView.toInt, ...)`) bump `viewNumber`
          // but never this field, so `validateProposalVcc` and the leader-side `vccMissing`
          // gate can distinguish the round-start view from a real view-change quorum
          // (VCC required). Without this stamping the validator rejected every round-start
          // proposal at `viewNumber > 0` with `view{N}_proposal_missing_vcc`.
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
            "priorAbandonmentCount" -> priorAbandonmentCount.toString,
            "viewSeed" -> "certified_or_zero",
            "timeViewTimeoutHint" -> timeView.toString,
            "parentEndTimeMs" -> parentEndTimeMs.fold("none")(_.toString),
            "nowMs" -> nowMs.toString
          )
          val optionalPairs =
            (if (withdrawn.nonEmpty) Seq("withdrawn" -> withdrawn.size.toString) else Seq.empty) ++
              (if (penalizedPeers.nonEmpty) Seq("penalized" -> penalizedPeers.size.toString) else Seq.empty) ++
              (if (probationPeers.nonEmpty) Seq("probation" -> probationPeers.size.toString) else Seq.empty) ++
              (if (abandonedMissing.nonEmpty) Seq("abandonedMissing" -> abandonedMissing.size.toString) else Seq.empty) ++
              (if (priorAbandonmentCount > 0) Seq("suppressedRetryViewSeed" -> priorAbandonmentCount.toString) else Seq.empty)
          ConsensusLog.info(logger, Lifecycle, key.show, role, RoundStarted, (basePairs ++ optionalPairs): _*)
        }

      } yield (state, effect)
  }
}
