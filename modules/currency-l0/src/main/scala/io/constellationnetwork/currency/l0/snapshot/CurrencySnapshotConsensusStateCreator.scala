package io.constellationnetwork.currency.l0.snapshot

import cats.effect.kernel.Clock
import cats.effect.{Async, Sync}
import cats.syntax.all._

import io.constellationnetwork.currency.l0.snapshot.schema.{CollectingFacilities, CurrencyConsensusKind, CurrencyConsensusOutcome}
import io.constellationnetwork.currency.schema.CurrencyStateKey
import io.constellationnetwork.currency.schema.currency.CurrencySnapshotContext
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event}
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.Facility
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusPeerDeclaration
import io.constellationnetwork.node.shared.infrastructure.consensus.state.{ConsensusStateCreator, _}
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash

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
    facilitatorSelector: FacilitatorSelector,
    consensusConfigHash: Hash,
    peerQualityTracker: PeerQualityTracker[F],
    tcaFilter: TrailingCommonAncestorFilter[F],
    eventMempool: EventMempool[F, CurrencySnapshotEvent, CurrencyStateKey]
  ): CurrencySnapshotConsensusStateCreator[F] = new CurrencySnapshotConsensusStateCreator[F] {

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
        .flatTap(logIfCreated)

    private def facilitateConsensus(
      key: CurrencySnapshotKey,
      lastOutcome: CurrencyConsensusOutcome,
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
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

        // Peers that failed to participate in the previous round.
        // Two sources (both from consensus-agreed lastOutcome, so deterministic):
        // 1. nonSigners = facilitators - signers: peers who remained as facilitators but didn't sign
        // 2. removedFacilitators: peers evicted by StallDetector view change during the round
        // Without including removedFacilitators, evicted peers get re-selected every round
        // and waste ~33s of stall detection before being re-evicted.
        lastRoundFacilitators = lastOutcome.facilitators.value.toSet
        lastRoundSigners = lastOutcome.finished.signedMajorityArtifact.proofs.map(_.id.toPeerId).toSortedSet.toSet
        lastRoundEvicted = lastOutcome.removedFacilitators.value
        previouslyRemoved = (lastRoundFacilitators -- lastRoundSigners) ++ lastRoundEvicted

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

        // TCA (Trailing Common Ancestor): exclude degraded peers using proofs-based detection.
        // Compares lastOutcome.facilitators (who was supposed to sign) with the actual proofs on the
        // last finalized snapshot (who actually signed). Peers that were facilitators but did NOT sign
        // are degraded. 100% deterministic: both inputs come from consensus-agreed lastOutcome.
        lastFacilitators = lastOutcome.facilitators.value.toSet
        lastSigners = lastOutcome.finished.signedMajorityArtifact.proofs.map(_.id.toPeerId).toSortedSet.toSet
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
        genuinelyNewCandidates = allEligible.filterNot(previousEligibleSet.contains).toSet - selfId
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

        // For THIS round only: exclude recently removed and penalized peers from active selection.
        // They remain in allEligible so they can be re-selected in future rounds.
        // NOTE: abandonedMissing is intentionally NOT included — it's a local-only tracker that
        // can diverge between nodes, causing different facilitator sets → fork detection → Leaving state.
        //
        // MINIMUM VIABLE QUORUM: If excluding penalized peers would drop below majority,
        // bypass penalties and use all eligible peers. This prevents PeerQualityTracker from
        // reducing the facilitator set below viable consensus.
        // Dynamic majority: floor(N/2) + 1, matching StallDetector's quorum floor.
        minViableQuorum = math.max(3, (allEligible.size / 2) + 1)
        eligibleThisRound = {
          val excluded = previouslyRemoved ++ penalizedPeers ++ allDeferred
          val filtered = allEligible.filterNot(excluded.contains)
          val withoutPenaltiesOnly = allEligible.filterNot((previouslyRemoved ++ penalizedPeers).contains)
          if (filtered.size >= minViableQuorum) filtered
          else if (withoutPenaltiesOnly.size >= 2 && allDeferred.nonEmpty) withoutPenaltiesOnly
          else if (allEligible.size >= minViableQuorum) allEligible
          else if (allEligible.nonEmpty) allEligible
          else List(selfId)
        }

        penaltyBypassed = {
          val excluded = previouslyRemoved ++ penalizedPeers ++ allDeferred
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

        effect = for {
          eventHashes <- eventMempool.getEventHashes
          _ <- gossip.spread(
            ConsensusPeerDeclaration(
              key,
              Facility(
                eventHashes,
                candidates,
                maybeTrigger,
                lastOutcome.finished.facilitatorsHash,
                lastGlobalSnapshotOrdinal,
                lastOutcome.finished.snapshotHash,
                consensusConfigHash = consensusConfigHash.some
              )
            )
          )
        } yield ()

        // Quality-weighted leader selection using consensus-agreed integer quality scores
        leader = facilitatorSelector.selectLeaderWeighted(active, entropy, qualityScores = lastOutcome.peerQuality, qualityWeight = 0.3)

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
            "view" -> "0",
            "lastGlobalOrd" -> lastGlobalSnapshotOrdinal.show
          )
          val optionalPairs =
            (if (withdrawn.nonEmpty) Seq("withdrawn" -> withdrawn.size.toString) else Seq.empty) ++
              (if (penalizedPeers.nonEmpty) Seq("penalized" -> penalizedPeers.size.toString) else Seq.empty) ++
              (if (previouslyRemoved.nonEmpty) Seq("previouslyRemoved" -> previouslyRemoved.size.toString) else Seq.empty) ++
              (if (abandonedMissing.nonEmpty) Seq("abandonedMissing" -> abandonedMissing.size.toString) else Seq.empty) ++
              (if (allDeferred.nonEmpty) Seq("deferredCandidates" -> allDeferred.size.toString) else Seq.empty)
          ConsensusLog.info(logger, Category.Lifecycle, key.show, role, Event.RoundStarted, (basePairs ++ optionalPairs): _*)
        }

      } yield (state, effect)
  }
}
