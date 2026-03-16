package io.constellationnetwork.currency.l0.snapshot

import cats.effect.kernel.Clock
import cats.effect.{Async, Sync}
import cats.syntax.all._

import io.constellationnetwork.currency.l0.snapshot.schema.{CollectingFacilities, CurrencyConsensusKind, CurrencyConsensusOutcome}
import io.constellationnetwork.currency.schema.currency.CurrencySnapshotContext
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.Facility
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusPeerDeclaration
import io.constellationnetwork.node.shared.infrastructure.consensus.state.{ConsensusStateCreator, _}
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
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
    tcaFilter: TrailingCommonAncestorFilter[F]
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

        // TCA (Trailing Common Ancestor): exclude degraded peers based on historical snapshot signers.
        // Deterministic: all nodes read the same finalized snapshots, computing the same exclusion set.
        // Only peers that appeared in the lookback window but signed fewer than minParticipation snapshots
        // are excluded. New peers (zero appearances) are NOT excluded — they're presumed new joiners.
        // Falls back to fullBase (no exclusions) when insufficient history is available.
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
        abandonedMissing <- peerQualityTracker.getAndClearAbandonedMissingPeers

        _ <- logger
          .info(
            s"[CONSENSUS] Abandoned-missing peers (not excluded, logged only) for key=$key: " +
              s"[${abandonedMissing.toList.map(_.value.value.take(8)).mkString(",")}]"
          )
          .whenA(abandonedMissing.nonEmpty)

        // For THIS round only: exclude recently removed and penalized peers from active selection.
        // They remain in allEligible so they can be re-selected in future rounds.
        // NOTE: abandonedMissing is intentionally NOT included — it's local-only and non-deterministic.
        eligibleThisRound = {
          val excluded = previouslyRemoved ++ penalizedPeers
          val filtered = allEligible.filterNot(excluded.contains)
          if (filtered.isEmpty) List(selfId) else filtered
        }

        // Apply deterministic subset selection using hash-distance ordering
        // Uses the previous round's snapshot hash as entropy for randomization
        entropy = lastOutcome.finished.snapshotHash
        activeFacilitators = facilitatorSelector.select(eligibleThisRound, entropy)

        _ <- logger
          .info(
            s"Facilitator subsetting for key=$key: " +
              s"allEligible=${allEligible.size}, eligibleThisRound=${eligibleThisRound.size}, selected=${activeFacilitators.size}"
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

        effect = consensusStorage.getUpperBound.flatMap { bound =>
          gossip.spread(
            ConsensusPeerDeclaration(
              key,
              Facility(
                bound,
                candidates,
                maybeTrigger,
                lastOutcome.finished.facilitatorsHash,
                lastGlobalSnapshotOrdinal,
                lastOutcome.finished.snapshotHash,
                consensusConfigHash = consensusConfigHash.some
              )
            )
          )
        }

        // Quality-weighted leader selection using consensus-agreed quality scores
        qualityScores = lastOutcome.peerQuality.map {
          case (pid, (completed, participated)) =>
            pid -> (completed.toDouble / participated.max(1))
        }
        leader = facilitatorSelector.selectLeaderWeighted(active, entropy, qualityScores = qualityScores, qualityWeight = 0.3)

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

        role = if (leader === selfId) "LEADER" else "FOLLOWER"
        leaderScore <- peerQualityTracker.getQualityScore(leader)
        _ <- logger.info(
          s"[CONSENSUS:$role] Round STARTED key=$key trigger=${maybeTrigger.getOrElse("none")} lastGlobalOrd=${lastGlobalSnapshotOrdinal.show} " +
            s"facilitators=${active.size} eligible=${allEligible.size} candidates=${filteredCandidates.size} " +
            s"leader=${leader.show.take(8)}... leaderScore=${f"$leaderScore%.2f"} self=${selfId.show.take(8)}... view=0" +
            (if (withdrawn.nonEmpty) s" withdrawn=${withdrawn.size}" else "") +
            (if (penalizedPeers.nonEmpty) s" penalized=${penalizedPeers.size}" else "") +
            (if (previouslyRemoved.nonEmpty) s" previouslyRemoved=${previouslyRemoved.size}" else "") +
            (if (abandonedMissing.nonEmpty) s" abandonedMissing=${abandonedMissing.size}" else "") +
            s" entropy=${entropy.show.take(8)}..."
        )

      } yield (state, effect)
  }
}
