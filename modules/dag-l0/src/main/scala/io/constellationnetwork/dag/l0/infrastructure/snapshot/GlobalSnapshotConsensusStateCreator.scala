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
import io.constellationnetwork.schema.mpt.GlobalStateKey
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

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
    facilitatorSelector: FacilitatorSelector,
    consensusConfigHash: Hash,
    consensusConfig: ConsensusConfig,
    peerQualityTracker: PeerQualityTracker[F],
    tcaFilter: TrailingCommonAncestorFilter[F],
    eventMempool: EventMempool[F, GlobalSnapshotEvent, GlobalStateKey]
  ): GlobalSnapshotConsensusStateCreator[F] = new GlobalSnapshotConsensusStateCreator[F] {
    val config: ConsensusConfig = consensusConfig

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
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind]
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

        // Peers that failed to participate in the previous round.
        // Derived only from consensus-agreed lastOutcome.removedFacilitators (peers evicted by the
        // facility-phase fork-eviction path). Previously we also computed a "non-signers" set via
        // `lastFacilitators - signedMajorityArtifact.proofs`, but `proofs` is per-node-local —
        // different nodes see different proof subsets for the same snapshot, so the "non-signers"
        // set varied per node → divergent `previouslyRemoved` → divergent committees → forks. See
        // Phase 3 canonical-signers fix for the same bug in outcome-layer penalty derivation.
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

        // Multi-round candidate deferral: new peers must observe for candidateDeferralRounds
        // before actively participating. Uses a countdown carried in the consensus outcome
        // (same pattern as removalPenalties) for deterministic, consensus-agreed tracking.
        //
        // genuinelyNewCandidates: peers appearing in allEligible for the first time this round.
        // These will have their countdown initialized in the advancer.
        // deferredByCountdown: peers with an active countdown from previous rounds.
        // allDeferred: union of both, used for exclusion from eligibleThisRound.
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

        _ <- logger
          .debug(
            s"Removal penalties for key=$key: ${penalizedPeers.size} penalized peers" +
              (if (penalizedPeers.nonEmpty)
                 s" [${lastOutcome.removalPenalties.filter(_._2 > 0).map(kv => s"${kv._1.value.value.take(8)}:${kv._2}").mkString(",")}]"
               else "")
          )
          .whenA(penalizedPeers.nonEmpty)

        // Chronic non-signer filter: exclude peers from the committee if their historical
        // participation rate is below config.minParticipationRatio AFTER they have been
        // observed for at least config.minParticipationObservations rounds. Uses the
        // consensus-agreed peerQuality outcome field (completed, participated), so every
        // node computes the same chronicNonSigners set. This prevents flaky community peers
        // from being selected into the committee where they would cause mid-round stalls
        // and force eviction cascades (the "3-of-4-unresponsive" death spiral).
        //
        // Only peers with participated >= minParticipationObservations are subject to the
        // filter — new peers get a grace period to establish a track record.
        chronicNonSigners = lastOutcome.peerQuality.collect {
          case (pid, (completed, participated))
              if participated >= config.minParticipationObservations &&
                (completed.toDouble / participated.toDouble) < config.minParticipationRatio =>
            pid
        }.toSet

        _ <- ConsensusLog
          .info(
            logger,
            Facilitator,
            key.show,
            "n/a",
            ChronicNonSignersExcluded,
            "count" -> chronicNonSigners.size.toString,
            "minObservations" -> config.minParticipationObservations.toString,
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
            Facilitator,
            key.show,
            "n/a",
            AbandonedMissingLogged,
            "count" -> abandonedMissing.size.toString,
            "peers" -> abandonedMissing.toList.map(_.value.value.take(8)).mkString(",")
          )
          .whenA(abandonedMissing.nonEmpty)

        // For THIS round only: exclude recently removed and penalized peers from active selection.
        // They remain in allEligible so they can be re-selected in future rounds.
        // NOTE: abandonedMissing is intentionally NOT included — it's a local-only tracker that
        // can diverge between nodes, causing different facilitator sets → fork detection → Leaving state.
        //
        // MINIMUM VIABLE QUORUM: If excluding penalized peers would drop below majority,
        // bypass penalties and use all eligible peers. This prevents PeerQualityTracker from
        // reducing the facilitator set below viable consensus.
        // Dynamic majority: floor(N/2) + 1, matching StallDetector's quorum floor.
        // Penalties can never reduce the facilitator set below the majority threshold —
        // if they would, bypass them and let StallDetector handle truly unresponsive peers at runtime.
        minViableQuorum = math.max(3, (allEligible.size / 2) + 1)
        eligibleThisRound = {
          // Exclude: previously removed peers, penalized peers, chronic non-signers,
          // AND deferred candidates (both brand-new and those still in countdown).
          // Deferred candidates remain in allEligible so they remain tracked.
          //
          // chronicNonSigners are kept out even through the withoutPenaltiesOnly bypass:
          // these are peers with established track records of NOT signing, distinct from
          // temporary penalties. Admitting them has been shown to stall rounds.
          val excluded = previouslyRemoved ++ penalizedPeers ++ chronicNonSigners ++ allDeferred
          val filtered = allEligible.filterNot(excluded.contains)
          // Bypass only penalties and deferred (not chronic non-signers) to keep the set
          // functional without forcing chronically-broken nodes in. A 2-node set can still
          // produce rounds.
          val withoutPenaltiesOnly = allEligible.filterNot((previouslyRemoved ++ penalizedPeers ++ chronicNonSigners).contains)
          if (filtered.size >= minViableQuorum) filtered
          else if (withoutPenaltiesOnly.size >= 2 && allDeferred.nonEmpty) withoutPenaltiesOnly
          else if (allEligible.size >= minViableQuorum) allEligible
          else if (allEligible.nonEmpty) allEligible
          else List(selfId)
        }

        penaltyBypassed = {
          val excluded = previouslyRemoved ++ penalizedPeers ++ chronicNonSigners ++ allDeferred
          val filtered = allEligible.filterNot(excluded.contains)
          filtered.size < minViableQuorum && allEligible.size > filtered.size
        }

        _ <- ConsensusLog
          .info(
            logger,
            Facilitator,
            key.show,
            "n/a",
            MinQuorumFloorApplied,
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
            Facilitator,
            key.show,
            "n/a",
            CandidateObserving,
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
        effect = for {
          eventHashes <- eventMempool.getEventHashes
          facility = Facility(
            eventHashes,
            candidates,
            maybeTrigger,
            lastOutcome.finished.facilitatorsHash,
            lastOutcome.key,
            lastOutcome.finished.snapshotHash,
            consensusConfigHash = consensusConfigHash.some
          )
          declaration = ConsensusPeerDeclaration(key, facility)
          _ <- consensusStorage.addFacility(selfId, key, facility)
          _ <- gossip.spreadDirect(declaration, active.toSet)
        } yield ()

        // Quality-weighted leader selection: use consensus-agreed quality scores
        // so all nodes compute the same leader deterministically.
        // Pass raw (completed, participated) integers — the selector uses integer-only
        // tier computation (tier = participated - completed = failure count) to avoid
        // platform-dependent float-to-long conversion differences.
        //
        // Graduation filter: restrict the leader pool to peers with `participated >=
        // minParticipationObservations` in the consensus-agreed peerQuality outcome.
        // Without this filter, a peer with no history defaults to tier 0 inside the
        // selector and ties with proven peers, handing the leader slot to unproven
        // community peers that often cannot fulfill the proposer role (forces a view
        // change that burns ~2 min of round time). Source nodes accumulate history
        // quickly and always qualify; fresh community entrants must demonstrate they
        // can complete rounds as facilitator before they can lead.
        //
        // The graduated pool must contain at least 2 peers for view rotation to be
        // meaningful — with a single peer, `viewNumber % 1 = 0` always returns the
        // same leader, making view change a no-op and deadlocking the cluster if the
        // sole graduated peer ever stalls. At genesis / cold start (nobody has
        // enough history yet), OR in a solo-bootstrap tail (only one peer graduated),
        // fall back to `active` — same as the pre-filter behavior, self-healing once
        // more peers reach the threshold.
        graduatedLeaderPool = active.filter { pid =>
          val (_, participated) = lastOutcome.peerQuality.getOrElse(pid, (0, 0))
          participated >= config.minParticipationObservations
        }
        leaderPool = if (graduatedLeaderPool.size >= 2) graduatedLeaderPool else active
        leader = facilitatorSelector.selectLeaderWeighted(leaderPool, entropy, qualityScores = lastOutcome.peerQuality)

        _ <- ConsensusLog.info(
          logger,
          Facilitator,
          key.show,
          if (leader === selfId) "Leader" else "Validator",
          FacilitatorsFinalized,
          "eligible" -> allEligible.size.toString,
          "active" -> active.size.toString,
          "excluded" -> (allEligible.size - eligibleThisRound.size).toString,
          "leader" -> ConsensusLog.pid(leader)
        )

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
          eligibleFacilitators = EligibleFacilitators(allEligible),
          leader = leader,
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
            "view" -> "0"
          )
          val optionalPairs =
            (if (withdrawn.nonEmpty) Seq("withdrawn" -> withdrawn.size.toString) else Seq.empty) ++
              (if (penalizedPeers.nonEmpty) Seq("penalized" -> penalizedPeers.size.toString) else Seq.empty) ++
              (if (previouslyRemoved.nonEmpty) Seq("previouslyRemoved" -> previouslyRemoved.size.toString) else Seq.empty) ++
              (if (abandonedMissing.nonEmpty) Seq("abandonedMissing" -> abandonedMissing.size.toString) else Seq.empty) ++
              (if (allDeferred.nonEmpty) Seq("deferredCandidates" -> allDeferred.size.toString) else Seq.empty)
          ConsensusLog.info(logger, Lifecycle, key.show, role, RoundStarted, (basePairs ++ optionalPairs): _*)
        }

      } yield (state, effect)
  }
}
