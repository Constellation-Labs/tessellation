package io.constellationnetwork.currency.l0.snapshot

import cats.effect.kernel.Clock
import cats.effect.{Async, Sync}
import cats.syntax.all._

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
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
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
    localHealthMonitor: LocalHealthMonitor[F]
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
        seedlistPeerIds = seedlist.map(_.map(_.peerId)).getOrElse(Set.empty)

        filteredPreviousEligible = previousEligible
          .filter(peerId => seedlist.isEmpty || seedlistPeerIds.contains(peerId))

        filteredCandidates = approvedCandidates
          .filter(peerId => seedlist.isEmpty || seedlistPeerIds.contains(peerId))

        previousEligibleSet = filteredPreviousEligible.toSet

        // previouslyRemoved from consensus-agreed lastOutcome.removedFacilitators only. See dag-l0
        // mirror for rationale: `signedMajorityArtifact.proofs` is per-node-local, its use here
        // caused divergent committees.
        lastRoundEvicted = lastOutcome.removedFacilitators.value
        previouslyRemoved = lastRoundEvicted

        // Full base WITHOUT removal filter — so removed peers can re-enter in future rounds.
        // The removal filter is only applied for active selection THIS round (see eligibleThisRound below).
        // Note: selfId is NOT unconditionally added here. Each node adding its own selfId creates
        // a unique facilitator set per node, causing fork detection (facilitatorsHash mismatch) and
        // permanent divergence. Instead, nodes join via the candidate registration mechanism:
        //   1. New node registers as candidate → included in next Facility declaration's candidates
        //   2. Next round: filteredCandidates includes the new node → enters fullBase
        //   3. deferralCountdown observes for candidateDeferralRounds → active after countdown expires
        // Genesis ordinal 1 (empty previousEligible + empty candidates) is handled by the
        // allEligible fallback below: `if (list.isEmpty) List(selfId)`.
        fullBase = (filteredPreviousEligible ++ filteredCandidates).distinct

        _ <- logger.debug(
          s"Facilitator selection for key=$key: " +
            s"previousEligible=${filteredPreviousEligible.size}, " +
            s"candidates=${filteredCandidates.size}, " +
            s"fullBase=${fullBase.size}" +
            (if (previouslyRemoved.nonEmpty) s", excludedFromPreviousRound=${previouslyRemoved.size}" else "")
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

        // Multi-round candidate deferral: new peers must observe for candidateDeferralRounds
        // before actively participating. Uses a countdown carried in the consensus outcome
        // (same pattern as removalPenalties) for deterministic, consensus-agreed tracking.
        genuinelyNewCandidates = allEligible.filterNot(previousEligibleSet.contains).toSet
        deferredByCountdown = lastOutcome.deferralCountdown.filter(_._2 > 0).keySet.intersect(allEligible.toSet)
        allDeferred = genuinelyNewCandidates ++ deferredByCountdown

        filteredOutByCollateral = fullBase.filterNot(allEligible.contains)
        _ <- filteredOutByCollateral.traverse_ { peerId =>
          logger.debug(s"Facilitator ${peerId.show} removed by facilitatorFilter for key=$key")
        }

        // Multi-round removal penalty: peers removed in prior rounds stay excluded
        // for removalPenaltyRounds rounds. Deterministic: derived from agreed-upon lastOutcome.
        penalizedPeers = lastOutcome.removalPenalties.filter(_._2 > 0).keySet

        // B2 re-admission probation: see dag-l0 mirror for full rationale.
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

        // Chronic non-signer filter: exclude peers from the committee if their historical
        // participation rate is below config.minParticipationRatio AFTER they have been
        // observed for at least config.minParticipationObservations rounds. See dag-l0
        // GlobalSnapshotConsensusStateCreator for full rationale.
        //
        // v8 (2026-04-29) minimum-history floor mirror: see dag-l0 site for Design B context.
        chronicNonSigners = lastOutcome.peerQuality.collect {
          case (pid, (completed, participated))
              if participated >= config.minParticipationObservations &&
                participated >= config.minObservationHistoryFloor &&
                (completed.toDouble / participated.toDouble) < config.minParticipationRatio =>
            pid
        }.toSet

        // Expose chronic-classification state via Prometheus — see dag-l0 mirror.
        _ <- Metrics[F].updateGauge("dag_currency_consensus_chronic_non_signers_count", chronicNonSigners.size.toLong)
        peerIdLabel = Metrics.unsafeLabelName("peer_id")
        _ <- lastOutcome.peerQuality.toList.traverse_ {
          case (pid, (completed, participated)) =>
            val ratio = if (participated > 0) completed.toDouble / participated.toDouble else 1.0
            val pidTag: Metrics.TagSeq = Seq((peerIdLabel, pid.value.value.take(8)))
            Metrics[F].updateGauge("dag_currency_consensus_peer_quality_ratio", ratio, pidTag) >>
              Metrics[F].updateGauge("dag_currency_consensus_peer_quality_participated", participated.toLong, pidTag) >>
              Metrics[F].updateGauge("dag_currency_consensus_peer_quality_completed", completed.toLong, pidTag)
        }

        _ <- ConsensusLog
          .info(
            logger,
            Category.Facilitator,
            key.show,
            "n/a",
            Event.ChronicNonSignersExcluded,
            "count" -> chronicNonSigners.size.toString,
            "minObservations" -> config.minParticipationObservations.toString,
            "historyFloor" -> config.minObservationHistoryFloor.toString,
            "minRatio" -> f"${config.minParticipationRatio}%.2f",
            "peers" -> chronicNonSigners.toList.map { pid =>
              val (c, p) = lastOutcome.peerQuality.getOrElse(pid, (0, 0))
              s"${pid.value.value.take(8)}:$c/$p"
            }
              .mkString(",")
          )
          .whenA(chronicNonSigners.nonEmpty)

        // Clear abandoned-missing tracking (but don't use it for exclusion — it's local-only and
        // causes non-deterministic facilitator sets across nodes, leading to fork detection failures).
        // The deterministic mechanisms (previouslyRemoved + penalizedPeers from consensus-agreed
        // lastOutcome) already handle unresponsive peer exclusion.
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

        // Prior-round-missing exclusion (Layer 2-Lite, 2026-05-16). Mirrors the dag-l0
        // implementation. Peers in the prior round's round-start committee that did NOT sign
        // the finalized outcome are excluded from THIS round's committee. Consensus-agreed
        // signal (every node has the byte-identical `Signed[Artifact]`), so no fork risk.
        // One-round memory only.
        priorRoundSigners = lastOutcome.finished.signedMajorityArtifact.proofs.toList.map(_.id.toPeerId).toSet
        priorRoundFacilitators = lastOutcome.facilitators.value.toSet
        priorRoundMissing = priorRoundFacilitators -- priorRoundSigners

        _ <- ConsensusLog
          .info(
            logger,
            Category.Facilitator,
            key.show,
            "n/a",
            Event.PriorRoundMissingExcluded,
            "count" -> priorRoundMissing.size.toString,
            "peers" -> priorRoundMissing.toList.map(_.value.value.take(8)).sorted.mkString(",")
          )
          .whenA(priorRoundMissing.nonEmpty)

        // Active-set tightening: when the recent-signers window is full, narrow the active
        // committee to peers that have signed at least `minParticipationInWindow` of the last
        // `tighteningWindow` rounds. Mirror of dag-l0 state creator; see
        // GlobalSnapshotConsensusStateCreator for the full rationale.
        tighteningWindowFull = lastOutcome.recentSigners.size >= config.tighteningWindow
        recentParticipants: Set[PeerId] =
          if (!tighteningWindowFull) Set.empty[PeerId]
          else {
            val counts: Map[PeerId, Int] =
              lastOutcome.recentSigners.values.iterator.flatten.toList
                .groupBy(identity)
                .view
                .mapValues(_.size)
                .toMap
            counts.collect {
              case (pid, n) if n >= config.minParticipationInWindow => pid
            }.toSet
          }
        tighteningTentativeExcluded: Set[PeerId] =
          if (!tighteningWindowFull) Set.empty[PeerId]
          else allEligible.toSet -- recentParticipants -- allDeferred
        tighteningPostFilterSize = allEligible.size - tighteningTentativeExcluded.size
        tighteningExcluded: Set[PeerId] =
          if (tighteningWindowFull && tighteningPostFilterSize >= config.activeFacilitatorFloor)
            tighteningTentativeExcluded
          else
            Set.empty[PeerId]

        _ <- ConsensusLog
          .info(
            logger,
            Category.Facilitator,
            key.show,
            "n/a",
            Event.ActiveSetTightened,
            "windowSize" -> lastOutcome.recentSigners.size.toString,
            "tighteningWindow" -> config.tighteningWindow.toString,
            "minParticipationInWindow" -> config.minParticipationInWindow.toString,
            "activeFacilitatorFloor" -> config.activeFacilitatorFloor.toString,
            "filterApplied" -> tighteningExcluded.nonEmpty.toString,
            "tentativeExcludedCount" -> tighteningTentativeExcluded.size.toString,
            "appliedExcludedCount" -> tighteningExcluded.size.toString,
            "postFilterSize" -> tighteningPostFilterSize.toString,
            "peers" -> tighteningExcluded.toList.map(_.value.value.take(8)).sorted.mkString(",")
          )
          .whenA(tighteningWindowFull)

        // For THIS round only: exclude recently removed and penalized peers from active selection.
        // They remain in allEligible so they can be re-selected in future rounds.
        // NOTE: abandonedMissing is intentionally NOT included — it's a local-only tracker that
        // can diverge between nodes, causing different facilitator sets → fork detection → Leaving state.
        //
        // MINIMUM VIABLE QUORUM — fork safety invariant (mirror of dag-l0 rationale).
        // Majority floor is computed over potentiallyCompeting (allEligible minus chronic
        // non-signers), not raw allEligible. Chronic non-signers are consensus-agreed peers
        // that cannot form a competing quorum by definition, so they don't count toward the
        // partition-shrink fork threshold. Lets the reliable cohort keep running when chronic
        // peers outnumber them, while still preventing a real partition-both-sides-shrink fork.
        // B2: probation peers excluded BEFORE minViableQuorum is computed. See dag-l0 mirror.
        potentiallyCompeting = allEligible.filterNot(pid => chronicNonSigners.contains(pid) || probationPeers.contains(pid))
        minViableQuorum = math.max(3, (potentiallyCompeting.size / 2) + 1)

        // Periodic reinstatement (Option A): every chronicReinstatementInterval ordinals,
        // rotate one chronic non-signer back into the eligible pool for a single round
        // so their peerQuality counters can resume accumulating. Deterministic rotation.
        reinstatedThisRound = {
          val interval = config.chronicReinstatementInterval
          val ordinalValue = key.value.value
          val isReinstatementRound = interval > 0 && ordinalValue % interval == 0L
          if (!isReinstatementRound || chronicNonSigners.isEmpty) Set.empty[PeerId]
          else {
            val sorted = chronicNonSigners.toList.sortBy(_.value.value)
            val idx = ((ordinalValue / interval) % sorted.size.toLong).toInt
            Set(sorted(idx))
          }
        }
        effectiveChronic = chronicNonSigners -- reinstatedThisRound

        eligibleThisRound = {
          // B2 probation is included in the excluded set AND the withoutPenaltiesOnly escape,
          // making it non-bypassable. See dag-l0 mirror for full rationale.
          // priorRoundMissing acts like a penalty: excluded normally, bypassable via
          // withoutPenaltiesOnly when liveness is at risk.
          // tighteningExcluded shares penalty semantics: excluded normally, lifts on
          // the withoutPenaltiesOnly bypass so a flaky cohort can still close rounds.
          val excluded =
            previouslyRemoved ++ penalizedPeers ++ effectiveChronic ++ allDeferred ++ probationPeers ++ priorRoundMissing ++
              tighteningExcluded
          val filtered = allEligible.filterNot(excluded.contains)
          val withoutPenaltiesOnly =
            allEligible.filterNot((previouslyRemoved ++ penalizedPeers ++ effectiveChronic ++ probationPeers).contains)
          val allEligibleMinusProbation = allEligible.filterNot(probationPeers.contains)
          if (filtered.size >= minViableQuorum) filtered
          else if (withoutPenaltiesOnly.size >= 2 && allDeferred.nonEmpty) withoutPenaltiesOnly
          else if (filtered.nonEmpty) filtered
          else if (allEligibleMinusProbation.nonEmpty) allEligibleMinusProbation
          else if (allEligible.nonEmpty) allEligible
          else List(selfId)
        }

        penaltyBypassed = {
          val excluded =
            previouslyRemoved ++ penalizedPeers ++ effectiveChronic ++ allDeferred ++ probationPeers ++ priorRoundMissing ++
              tighteningExcluded
          val filtered = allEligible.filterNot(excluded.contains)
          filtered.size < minViableQuorum && allEligible.size > filtered.size
        }

        _ <- ConsensusLog
          .info(
            logger,
            Category.Facilitator,
            key.show,
            "n/a",
            Event.MinQuorumFloorApplied,
            "filteredCount" -> allEligible
              .filterNot((previouslyRemoved ++ penalizedPeers ++ allDeferred).contains)
              .size
              .toString,
            "minViableQuorum" -> minViableQuorum.toString,
            "usingAll" -> allEligible.size.toString,
            "penalizedBypassed" -> penalizedPeers.size.toString,
            "removedBypassed" -> previouslyRemoved.size.toString,
            "deferredBypassed" -> allDeferred.size.toString
          )
          .whenA(penaltyBypassed)

        _ <- ConsensusLog
          .info(
            logger,
            Category.Facilitator,
            key.show,
            "n/a",
            Event.CandidateObserving,
            "deferredCount" -> allDeferred.size.toString,
            "deferredPeers" -> allDeferred.toList.map(ConsensusLog.pid).mkString(","),
            "newThisRound" -> genuinelyNewCandidates.size.toString,
            "countdownActive" -> deferredByCountdown.size.toString,
            "actuallyDeferred" -> (!penaltyBypassed).toString,
            "eligibleThisRound" -> eligibleThisRound.size.toString,
            "allEligible" -> allEligible.size.toString
          )
          .whenA(allDeferred.nonEmpty)

        // Apply deterministic subset selection using hash-distance ordering
        // Uses the previous round's snapshot hash as entropy for randomization
        entropy = lastOutcome.finished.snapshotHash
        activeFacilitators = facilitatorSelector.select(eligibleThisRound, entropy)

        _ <- ConsensusLog
          .info(
            logger,
            Category.Facilitator,
            key.show,
            "n/a",
            Event.FacilitatorSubsetting,
            "allEligible" -> allEligible.size.toString,
            "eligibleThisRound" -> eligibleThisRound.size.toString,
            "selected" -> activeFacilitators.size.toString
          )
          .whenA(activeFacilitators.size < allEligible.size)

        (withdrawn, active) = activeFacilitators.partition { peerId =>
          resources.withdrawalsMap.get(peerId).contains(CurrencyConsensusKind.Facility)
        }
        _ <- withdrawn.traverse_ { peerId =>
          logger.info(s"Facilitator ${peerId.show} has withdrawn from consensus at key=$key")
        }
        time <- Clock[F].monotonic
        lastGlobalSnapshotOrdinal <- lastGlobalSnapshotStorage.getOrdinal.map(_.getOrElse(SnapshotOrdinal.MinValue))

        // Build Facility once, self-store locally (no reliance on gossip self-loopback), then
        // direct-push to the active facilitator set. Matches the dag-l0 creator — see rationale there.
        effect = for {
          eventHashes <- eventMempool.getEventHashes
          // v15: see GlobalSnapshotConsensusStateCreator for full rationale -- the hint is
          // captured at effect run time so the most recent LocalHealthMonitor sample rides
          // with the outgoing Facility.
          selfHealth <- localHealthMonitor.current
          facility = Facility(
            eventHashes,
            candidates,
            maybeTrigger,
            lastOutcome.finished.facilitatorsHash,
            lastGlobalSnapshotOrdinal,
            lastOutcome.finished.snapshotHash,
            consensusConfigHash = consensusConfigHash.some,
            selfHealthHint = selfHealth.some
          )
          declaration = ConsensusPeerDeclaration(key, facility)
          _ <- consensusStorage.addFacility(selfId, key, facility)
          _ <- gossip.spreadDirect(declaration, active.toSet)
        } yield ()

        // Quality-weighted leader selection using consensus-agreed integer quality scores.
        // Graduation filter: restrict leader pool to peers with `participated >=
        // minParticipationObservations`, but require at least 2 graduated peers so
        // view rotation can actually rotate. See GlobalSnapshotConsensusStateCreator
        // for the full rationale.
        // v11 (2026-04-30): kick-fast leader graduation. Mirror of dag-l0; see
        // GlobalSnapshotConsensusStateCreator for full rationale. Adds `completed >= 1` so a
        // peer that has never finalized a round cannot lead — closes the same chronic-flaky
        // leader trap on metagraph layer.
        graduatedLeaderPool = active.filter { pid =>
          val (completed, participated) = lastOutcome.peerQuality.getOrElse(pid, (0, 0))
          participated >= config.minParticipationObservations && completed >= 1
        }
        leaderPool = if (graduatedLeaderPool.size >= 2) graduatedLeaderPool else active
        // Layer 1 view-carry-forward (2026-05-16): see dag-l0 mirror. priorAbandonmentCount
        // seeds the leader pick so same-key retries rotate to different initial leaders.
        leader = facilitatorSelector.selectLeaderWeighted(
          leaderPool,
          entropy,
          viewNumber = priorAbandonmentCount,
          qualityScores = lastOutcome.peerQuality,
          selfHealthHints = lastOutcome.peerSelfHealth,
          peerViewChanges = lastOutcome.peerViewChanges.toMap,
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
          "excluded" -> (allEligible.size - eligibleThisRound.size).toString,
          "leader" -> ConsensusLog.pid(leader)
        )

        state = ConsensusState[CurrencySnapshotKey, CurrencySnapshotStatus, CurrencyConsensusOutcome, CurrencyConsensusKind](
          key,
          lastOutcome,
          Facilitators(active),
          // Canonical round-start committee — frozen at creation, never mutated by withdrawals.
          Facilitators(active),
          CollectingFacilities(
            maybeTrigger,
            lastOutcome.finished.facilitatorsHash,
            lastOutcome.finished.snapshotHash
          ),
          time,
          withdrawnFacilitators = WithdrawnFacilitators(withdrawn.toSet),
          eligibleFacilitators = EligibleFacilitators(allEligible),
          leader = leader,
          // Mirror dag-l0: start at the retry count so view-change continues monotonically.
          viewNumber = priorAbandonmentCount,
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
            "view" -> priorAbandonmentCount.toString,
            "lastGlobalOrd" -> lastGlobalSnapshotOrdinal.show
          )
          val optionalPairs =
            (if (withdrawn.nonEmpty) Seq("withdrawn" -> withdrawn.size.toString) else Seq.empty) ++
              (if (penalizedPeers.nonEmpty) Seq("penalized" -> penalizedPeers.size.toString) else Seq.empty) ++
              (if (previouslyRemoved.nonEmpty) Seq("previouslyRemoved" -> previouslyRemoved.size.toString) else Seq.empty) ++
              (if (abandonedMissing.nonEmpty) Seq("abandonedMissing" -> abandonedMissing.size.toString) else Seq.empty) ++
              (if (priorRoundMissing.nonEmpty) Seq("priorRoundMissing" -> priorRoundMissing.size.toString) else Seq.empty) ++
              (if (allDeferred.nonEmpty) Seq("deferredCandidates" -> allDeferred.size.toString) else Seq.empty) ++
              (if (priorAbandonmentCount > 0) Seq("retryCount" -> priorAbandonmentCount.toString) else Seq.empty)
          ConsensusLog.info(logger, Category.Lifecycle, key.show, role, Event.RoundStarted, (basePairs ++ optionalPairs): _*)
        }

      } yield (state, effect)
  }
}
