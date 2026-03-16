package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.effect.Async
import cats.effect.kernel.{Clock, Sync}
import cats.syntax.all._

import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.{CollectingFacilities, GlobalConsensusKind, GlobalConsensusOutcome}
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.Facility
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusPeerDeclaration
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
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
    peerQualityTracker: PeerQualityTracker[F],
    tcaFilter: TrailingCommonAncestorFilter[F]
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
        previousEligible = lastOutcome.eligibleOrFacilitators
        approvedCandidates = lastOutcome.finished.candidates.value
        seedlistPeerIds = seedlist.map(_.map(_.peerId)).getOrElse(Set.empty)

        filteredPreviousEligible = previousEligible
          .filter(peerId => seedlist.isEmpty || seedlistPeerIds.contains(peerId))

        filteredCandidates = approvedCandidates
          .filter(peerId => seedlist.isEmpty || seedlistPeerIds.contains(peerId))

        // Peers removed or withdrawn in the previous round.
        // Deterministic: all nodes agreed on removedFacilitators and withdrawnFacilitators via majority vote.
        // Including withdrawnFacilitators prevents re-selecting peers that couldn't participate
        // (e.g., offline/unreachable) — avoids infinite retry loops with the same unresponsive facilitators.
        previouslyRemoved = lastOutcome.removedFacilitators.value ++ lastOutcome.withdrawnFacilitators.value

        // Full base WITHOUT removal filter — so removed peers can re-enter in future rounds.
        // The removal filter is only applied for active selection THIS round (see eligibleThisRound below).
        // Note: we do NOT filter by cluster state here because each node has a different local view
        // of peer states, making such filtering non-deterministic across the network. Instead, the
        // StallDetector handles unreachable peers via view change (proposal phase) and round abandon.
        fullBase = (filteredPreviousEligible ++ filteredCandidates :+ selfId).distinct

        _ <- logger.debug(
          s"Facilitator selection for key=$key: " +
            s"previousEligible=${filteredPreviousEligible.size}, " +
            s"candidates=${filteredCandidates.size}, " +
            s"fullBase=${fullBase.size}" +
            (if (previouslyRemoved.nonEmpty) s", excludedFromPreviousRound=${previouslyRemoved.size}" else "")
        )

        // TCA (Trailing Common Ancestor): exclude degraded peers using early/recent split.
        // Splits the lookback window into early and recent regions. A peer is degraded if it signed
        // early snapshots but NOT any recent ones (was active, now silent). New peers that only appear
        // in recent snapshots are NOT excluded. Deterministic: all nodes read the same finalized snapshots.
        tcaDegraded <- tcaFilter.degradedPeers(key)
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
            ConsensusLog.Facilitator,
            key.show,
            "n/a",
            "event" -> "TCA_FILTER_APPLIED",
            "tcaDegraded" -> degraded.size.toString,
            "fullBase" -> fullBase.size.toString,
            "tcaFiltered" -> tcaFilteredBase.size.toString
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
            ConsensusLog.Facilitator,
            key.show,
            "n/a",
            "event" -> "ABANDONED_MISSING_LOGGED",
            "count" -> abandonedMissing.size.toString,
            "peers" -> abandonedMissing.toList.map(_.value.value.take(8)).mkString(",")
          )
          .whenA(abandonedMissing.nonEmpty)

        // For THIS round only: exclude recently removed and penalized peers from active selection.
        // They remain in allEligible so they can be re-selected in future rounds.
        // NOTE: abandonedMissing is intentionally NOT included — it's a local-only tracker that
        // can diverge between nodes, causing different facilitator sets → fork detection → Leaving state.
        eligibleThisRound = {
          val excluded = previouslyRemoved ++ penalizedPeers
          val filtered = allEligible.filterNot(excluded.contains)
          if (filtered.isEmpty) List(selfId) else filtered
        }

        // Apply deterministic subset selection using hash-distance ordering
        // Uses the previous round's snapshot hash as entropy for randomization
        entropy = lastOutcome.finished.snapshotHash
        activeFacilitators = facilitatorSelector.select(eligibleThisRound, entropy)

        _ <- ConsensusLog
          .info(
            logger,
            ConsensusLog.Facilitator,
            key.show,
            "n/a",
            "event" -> "FACILITATOR_SUBSETTING",
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
                lastOutcome.finished.snapshotHash,
                consensusConfigHash = consensusConfigHash.some
              )
            )
          )
        }

        // Quality-weighted leader selection: use consensus-agreed quality scores
        // so all nodes compute the same leader deterministically.
        qualityScores = lastOutcome.peerQuality.map {
          case (pid, (completed, participated)) =>
            pid -> (completed.toDouble / participated.max(1))
        }
        leader = facilitatorSelector.selectLeaderWeighted(active, entropy, qualityScores = qualityScores, qualityWeight = 0.3)

        _ <- ConsensusLog.info(
          logger,
          ConsensusLog.Facilitator,
          key.show,
          if (leader === selfId) "Leader" else "Validator",
          "event" -> "FACILITATORS_FINALIZED",
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
            "event" -> "ROUND_STARTED",
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
              (if (abandonedMissing.nonEmpty) Seq("abandonedMissing" -> abandonedMissing.size.toString) else Seq.empty)
          ConsensusLog.info(logger, ConsensusLog.Lifecycle, key.show, role, (basePairs ++ optionalPairs): _*)
        }

      } yield (state, effect)
  }
}
