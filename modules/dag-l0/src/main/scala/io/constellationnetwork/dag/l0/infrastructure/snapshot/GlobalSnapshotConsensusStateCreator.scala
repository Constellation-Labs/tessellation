package io.constellationnetwork.dag.l0.infrastructure.snapshot

import java.security.KeyPair

import cats.MonadThrow
import cats.effect.Async
import cats.effect.kernel.{Clock, Ref, Sync}
import cats.effect.std.Queue
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NoStackTrace

import io.constellationnetwork.dag.l0.infrastructure.mempool.DagAwaitingParentConfig
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.GlobalSnapshotEvent
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.{CollectingFacilities, GlobalConsensusKind, GlobalConsensusOutcome}
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.Category._
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.Event._
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{EvictionReason, Facility}
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.{ConsensusCommand, EvictionVoter}
import io.constellationnetwork.node.shared.infrastructure.consensus.message.{ConsensusPeerDeclaration, ConsensusPeerEvictionVote}
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.ID.IdOps
import io.constellationnetwork.schema.mpt.GlobalStateKey
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.{HasherSelector, SecurityProvider}

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

  /** Build the round-start atomic-replacement effect without running it. The state creator retains this effect with the new consensus
    * state, so the voter can read the committed round before it signs or stores a vote. Assembly is checked before the first Facility.
    */
  private[snapshot] def atomicReplacementRoundStartEffect[F[_]: Sync](
    alreadyVoted: Boolean,
    emitVote: F[Unit],
    checkAssembly: F[Unit]
  ): F[Unit] =
    (if (alreadyVoted) Sync[F].unit else emitVote) >> checkAssembly

  /** This build contains no pre-v35 Global L0 engine. Keys below the certified-consensus activation are replayed or downloaded, never
    * produced; a node asked to facilitate one fails closed instead of signing with retired rules.
    */
  final case class CertifiedConsensusNotActiveForProduction(key: GlobalSnapshotKey, activationKey: Long) extends NoStackTrace {
    override def getMessage: String =
      s"Refusing to produce GL0 snapshot ordinal=${key.value.value}: certified-consensus activation=$activationKey has not been " +
        "reached and this build has no pre-v35 consensus engine. Pin snapshot.certified-consensus-activation-ordinal to the recovery " +
        "anchor and enter certified consensus through the recovery-seed rollback."
  }

  private[snapshot] def requireCertifiedProduction[F[_]: MonadThrow](
    config: ConsensusConfig,
    key: GlobalSnapshotKey
  ): F[Unit] =
    CertifiedConsensusNotActiveForProduction(key, config.certifiedConsensusActivationKey)
      .raiseError[F, Unit]
      .unlessA(config.certifiedConsensusActiveAt(key.value.value))

  private[snapshot] final case class EvictionVoteRetransmission(
    target: PeerId,
    vote: io.constellationnetwork.security.signature.Signed[
      io.constellationnetwork.node.shared.infrastructure.consensus.declaration.EvictionVote
    ],
    recipients: Set[PeerId]
  )

  /** Reuse the exact stored self vote; never re-sign during retry. An already-assembled certificate terminates retransmission. */
  private[snapshot] def evictionVoteRetransmission(
    selfId: PeerId,
    resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind],
    currentCore: Set[PeerId],
    currentSigningCommittee: Set[PeerId],
    assembled: Set[io.constellationnetwork.node.shared.infrastructure.consensus.declaration.EvictionCertificate]
  ): Option[EvictionVoteRetransmission] =
    resources.evictionVotes.toList
      .sortBy(_._1.value.value)
      .iterator
      .flatMap {
        case (target, voters) if currentSigningCommittee.contains(target) && !assembled.exists(_.targetPeer === target) =>
          voters
            .get(selfId)
            .filter(_.value.reason === EvictionReason.Silent)
            .iterator
            .map(vote => EvictionVoteRetransmission(target, vote, currentCore - selfId - target))
        case _ => Iterator.empty
      }
      .nextOption()

  def make[F[_]: Async: Metrics: HasherSelector: SecurityProvider](
    consensusFns: GlobalSnapshotConsensusFunctions[F],
    consensusStorage: GlobalConsensusStorage[F],
    gossip: Gossip[F],
    selfId: PeerId,
    networkId: String,
    keyPair: KeyPair,
    seedlist: Option[Set[SeedlistEntry]],
    facilitatorSelector: FacilitatorSelector,
    consensusConfigHash: Hash,
    consensusConfig: ConsensusConfig,
    peerQualityTracker: PeerQualityTracker[F],
    eventMempool: EventMempool[F, GlobalSnapshotEvent, GlobalStateKey],
    evictionVoter: EvictionVoter[F, GlobalSnapshotKey],
    consensusQueue: Queue[
      F,
      ConsensusCommand[GlobalSnapshotKey, GlobalSnapshotArtifact, GlobalSnapshotContext, GlobalConsensusOutcome]
    ],
    finalityParticipationMissHistoryRef: Ref[F, FinalityParticipationAuditor.MissHistory],
    membershipPolicy: HealthDerivedMembershipPolicy
  ): GlobalSnapshotConsensusStateCreator[F] = new GlobalSnapshotConsensusStateCreator[F] {
    val config: ConsensusConfig = consensusConfig

    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

    private val dagAwaitingParentConfig = DagAwaitingParentConfig.default
    private val maxAwaitingParentReactivationPerRound = 128

    /** Track every auditable parent-round signing-committee member from actual local snapshot proofs and audit one deterministic target.
      * Reuse the existing B1 vote path only after the round-count and elapsed-time miss floors, on the existing membership-change cadence,
      * and in the exact dead band where the current committee remains finalizable but one additional seat is unsupported.
      *
      * The local proof subset, monotonic time, and resulting headroom decision are evidence for local vote emission only. They never enter
      * the outcome, artifact, committee hash, or state proof. The committee-quorum EvictionCertificate has no standalone removal authority
      * on this path: it must be paired one-for-one with a committee-quorum open ReadyAtTip AdmissionCertificate in the certified
      * ProposalValue.
      */
    private def auditSigningFinalityParticipation(
      key: GlobalSnapshotKey,
      lastOutcome: GlobalConsensusOutcome,
      currentCore: Set[PeerId],
      currentSigningCommittee: Set[PeerId],
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind],
      observedAt: FiniteDuration,
      cadenceAllowed: Boolean
    ): F[F[Unit]] = {
      // Parent round committee from the certified parent QC, not from controller evidence.
      val parentRoundCommittee = lastOutcome.certifiedRoundCommittee
      val locallyObservedParentSigners =
        lastOutcome.finished.signedMajorityArtifact.proofs.toList.map(_.id.toPeerId).toSet
      val inBootstrap =
        !BootstrapCommittee.reached(lastOutcome.recentCommitteeSizes.values)
      val outcomeLabel = Metrics.unsafeLabelName("outcome")

      parentRoundCommittee match {
        case None =>
          // The recovery-seed root has no certified parent round. Clear local streaks and skip rather than bridging an
          // unknown round into a false consecutive-miss sequence.
          (finalityParticipationMissHistoryRef.set(FinalityParticipationAuditor.MissHistory.empty) >>
            Metrics[F]
              .incrementCounter(
                "dag_consensus_signing_finality_audit_total",
                Seq(outcomeLabel -> "missing_parent_evidence")
              )
              .whenA(!inBootstrap && currentCore.contains(selfId))).as(Sync[F].unit)

        case Some(parentCommittee) =>
          val currentCommittee = currentSigningCommittee
          val finalityHeadroom = FinalityHeadroom.evaluate(
            currentCommittee,
            locallyObservedParentSigners,
            consensusConfig.quorumThresholdFraction
          )
          val requiredMissDuration = FinalityParticipationAuditor.minimumMissDuration(
            consensusConfig.timeTriggerInterval,
            FinalityParticipationAuditor.DefaultRequiredConsecutiveMisses
          )
          val recordHeadroom =
            (Metrics[F].updateGauge(
              "dag_consensus_signing_finality_audit_current_committee_size",
              finalityHeadroom.currentCommitteeSize.toLong
            ) >>
              Metrics[F].updateGauge(
                "dag_consensus_signing_finality_audit_observed_current_committee_signers",
                finalityHeadroom.observedCurrentCommitteeSigners.toLong
              ) >>
              Metrics[F].updateGauge(
                "dag_consensus_signing_finality_audit_current_finality_floor",
                finalityHeadroom.currentFinalityFloor.toLong
              ) >>
              Metrics[F].updateGauge(
                "dag_consensus_signing_finality_audit_current_finality_margin",
                finalityHeadroom.currentMargin.toLong
              ) >>
              Metrics[F].updateGauge(
                "dag_consensus_signing_finality_audit_next_committee_size",
                finalityHeadroom.nextCommitteeSize.toLong
              ) >>
              Metrics[F].updateGauge(
                "dag_consensus_signing_finality_audit_next_finality_floor",
                finalityHeadroom.nextFinalityFloor.toLong
              ) >>
              Metrics[F].updateGauge(
                "dag_consensus_signing_finality_audit_finality_margin",
                finalityHeadroom.margin.toLong
              ) >>
              Metrics[F].updateGauge(
                "dag_consensus_signing_finality_audit_membership_hold",
                if (finalityHeadroom.holdsMembership) 1L else 0L
              ) >>
              Metrics[F].updateGauge(
                "dag_consensus_signing_finality_audit_cadence_allowed",
                if (cadenceAllowed) 1L else 0L
              ) >>
              Metrics[F].updateGauge(
                "dag_consensus_signing_finality_audit_atomic_replacement_allowed",
                if (finalityHeadroom.allowsAtomicReplacement) 1L else 0L
              )).whenA(currentCore.contains(selfId) && !inBootstrap)

          recordHeadroom >> finalityParticipationMissHistoryRef.modify { previous =>
            val observation = FinalityParticipationAuditor.observe(
              selfId,
              currentCore,
              currentSigningCommittee,
              parentCommittee,
              locallyObservedParentSigners,
              lastOutcome.key.value.value,
              lastOutcome.finished.snapshotHash,
              DeterministicRotation.opportunityIndex(key.value.value, config.activeAdmissionExpansionIntervalRounds),
              observedAt,
              requiredMissDuration,
              inBootstrap,
              previous
            )
            (observation.history, observation.decision)
          }.flatMap {
            case Some(audit) if FinalityParticipationAuditor.shouldEmitAtomicReplacementVote(audit, finalityHeadroom, cadenceAllowed) =>
              val alreadyVoted = resources.evictionVotes.get(audit.target).exists(_.contains(selfId))
              val roundStartEffect = GlobalSnapshotConsensusStateCreator.atomicReplacementRoundStartEffect(
                alreadyVoted,
                evictionVoter.emitEvictionVote(key, audit.target, EvictionReason.Silent),
                consensusQueue.offer(ConsensusCommand.CheckEvictionAssembly(key, audit.target))
              ) >>
                ConsensusLog.info(
                  logger,
                  Facilitator,
                  key.show,
                  "n/a",
                  Eviction,
                  "stage" -> "signing_finality_audit",
                  "target" -> ConsensusLog.pid(audit.target),
                  "signatureObserved" -> "false",
                  "consecutiveMisses" -> audit.consecutiveMisses.toString,
                  "requiredConsecutiveMisses" -> audit.requiredConsecutiveMisses.toString,
                  "consecutiveMissDurationMs" -> audit.consecutiveMissDuration.toMillis.toString,
                  "requiredMissDurationMs" -> audit.requiredMissDuration.toMillis.toString,
                  "currentCommitteeSize" -> finalityHeadroom.currentCommitteeSize.toString,
                  "observedCurrentCommitteeSigners" -> finalityHeadroom.observedCurrentCommitteeSigners.toString,
                  "currentFinalityFloor" -> finalityHeadroom.currentFinalityFloor.toString,
                  "currentFinalityMargin" -> finalityHeadroom.currentMargin.toString,
                  "nextFinalityFloor" -> finalityHeadroom.nextFinalityFloor.toString,
                  "nextSeatFinalityMargin" -> finalityHeadroom.nextSeatMargin.toString,
                  "cadenceAllowed" -> cadenceAllowed.toString,
                  "alreadyVoted" -> alreadyVoted.toString
                ) >>
                Metrics[F].incrementCounter(
                  "dag_consensus_signing_finality_audit_total",
                  Seq(outcomeLabel -> (if (alreadyVoted) "already_voted" else "vote_emitted"))
                )

              Sync[F].pure(roundStartEffect)

            case Some(audit) if audit.signatureObserved =>
              Metrics[F]
                .incrementCounter(
                  "dag_consensus_signing_finality_audit_total",
                  Seq(outcomeLabel -> "signature_observed")
                )
                .as(Sync[F].unit)

            case Some(audit) =>
              val outcome =
                if (!audit.roundHysteresisSatisfied) "round_hysteresis_wait"
                else if (!audit.durationHysteresisSatisfied) "duration_hysteresis_wait"
                else if (finalityHeadroom.allowsExpansion) "headroom_preserved"
                else if (finalityHeadroom.allowsSilentEviction) "below_current_floor"
                else if (!cadenceAllowed) "off_cadence"
                else "vote_suppressed"

              ConsensusLog.info(
                logger,
                Facilitator,
                key.show,
                "n/a",
                Eviction,
                "stage" -> "signing_finality_audit_suppressed",
                "outcome" -> outcome,
                "target" -> ConsensusLog.pid(audit.target),
                "consecutiveMisses" -> audit.consecutiveMisses.toString,
                "requiredConsecutiveMisses" -> audit.requiredConsecutiveMisses.toString,
                "consecutiveMissDurationMs" -> audit.consecutiveMissDuration.toMillis.toString,
                "requiredMissDurationMs" -> audit.requiredMissDuration.toMillis.toString,
                "currentCommitteeSize" -> finalityHeadroom.currentCommitteeSize.toString,
                "observedCurrentCommitteeSigners" -> finalityHeadroom.observedCurrentCommitteeSigners.toString,
                "currentFinalityFloor" -> finalityHeadroom.currentFinalityFloor.toString,
                "currentFinalityMargin" -> finalityHeadroom.currentMargin.toString,
                "nextFinalityFloor" -> finalityHeadroom.nextFinalityFloor.toString,
                "nextSeatFinalityMargin" -> finalityHeadroom.nextSeatMargin.toString,
                "cadenceAllowed" -> cadenceAllowed.toString
              ) >>
                Metrics[F]
                  .incrementCounter(
                    "dag_consensus_signing_finality_audit_total",
                    Seq(outcomeLabel -> outcome)
                  )
                  .as(Sync[F].unit)

            case None => Sync[F].pure(Sync[F].unit)
          }
      }
    }

    def tryFacilitateConsensus(
      key: GlobalSnapshotKey,
      lastOutcome: GlobalConsensusOutcome,
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind],
      priorAbandonmentCount: Int,
      expectedRoundStartFacilitators: Option[SortedSet[PeerId]]
    ): F[StateCreateResult] =
      requireCertifiedProduction[F](config, key) >>
        consensusStorage.resumePendingStateEffect(key) >>
        consensusStorage
          .condModifyStateWithSideEffect(key)(
            toCreateStateFn(
              validateExpectedRoundStartFacilitators(
                facilitateConsensus(key, lastOutcome, maybeTrigger, resources, priorAbandonmentCount),
                expectedRoundStartFacilitators
              )
            )
          )
          .map(_.flatten)
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
        seedlistPeerIds = seedlist.fold(List.empty[PeerId])(_.toList.map(_.peerId))
        certifiedNextRoundAuthority = lastOutcome.finished.certifiedOutcome
          .map(_.proposalQc.value.nextRoundAuthority)
        certifiedRoundProjection = certifiedNextRoundAuthority.map { authority =>
          val committee = CertifiedRoundCommitteeProjector.fromCertifiedAuthority(key, authority, config)

          authority.facilitators.toSortedSet.toList -> committee
        }

        filteredPreviousEligible = previousEligible
          .filter(peerId => seedlistPeerIds.isEmpty || seedlistPeerIds.contains(peerId))

        filteredCandidates = approvedCandidates
          .filter(peerId => seedlistPeerIds.isEmpty || seedlistPeerIds.contains(peerId))

        // Full base. Use only the parent round's canonical facilitator set. In schema v34
        // `finished.candidates` carries the accepted Proposal's open-admission nominee for vote
        // convergence; nomination alone is not membership authority. Only a quorum-certified
        // admission may change this base.
        fullBase = lastOutcome.facilitators.value
          .filter(peerId => seedlistPeerIds.isEmpty || seedlistPeerIds.contains(peerId))
          .distinct

        _ <- logger.debug(
          s"Facilitator selection for key=$key: " +
            s"previousEligible=${filteredPreviousEligible.size}, " +
            s"candidates=${filteredCandidates.size}, " +
            s"fullBase=${fullBase.size}"
        )

        // All eligible after collateral filtering (includes previously removed peers so they can re-enter)
        collateralEligible <- fullBase.filterA { peerId =>
          val seedlistAllows = seedlistPeerIds.isEmpty || seedlistPeerIds.contains(peerId)
          consensusFns
            .facilitatorFilter(
              lastOutcome.finished.signedMajorityArtifact,
              lastOutcome.finished.context,
              peerId
            )
            .map(seedlistAllows && _)
        }
        allEligible = if (collateralEligible.isEmpty) List(selfId) else collateralEligible

        filteredOutByCollateral = fullBase.filterNot(allEligible.contains)
        _ <- filteredOutByCollateral.traverse_ { peerId =>
          logger.debug(s"Facilitator ${peerId.show} removed by facilitatorFilter for key=$key")
        }

        // Multi-round removal penalty (the security tier of the eligibility pipeline). Peers
        // evicted via consensus-witnessed fork-eviction stay excluded for `removalPenaltyRounds`
        // rounds before they can re-enter at all. This is the only behavioural filter; there is
        // no score or tier partition. Deterministic: derived from consensus-agreed lastOutcome.
        // Stage 4 cert-anchored penalties: a peer with `penaltyUntil(peer) > key` is excluded
        // by pure ordinal comparison against the consensus-agreed key -- no per-round countdown
        // that a restart could observe half-decremented. Unioned into `penalizedPeers` so the
        // fallback ladder below treats both penalty families identically (bypassable on the
        // last liveness rung, unlike non-bypassable probation). Empty until the first
        // EvictionCertificate is applied post-deploy, so pre-deploy behavior is unchanged.
        certPenalizedPeers = lastOutcome.penaltyUntil
          .getOrElse(SortedMap.empty[PeerId, GlobalSnapshotKey])
          .filter { case (_, until) => until.value.value > key.value.value }
          .keySet
        penalizedPeers = lastOutcome.removalPenalties.filter(_._2 > 0).keySet ++ certPenalizedPeers

        // B2 re-admission probation: peers whose `removalPenalty` just expired sit in
        // `readmissionCountdown` for `readmissionProbationRounds` before they can re-enter
        // the committee. The normal path requires a consensus-witnessed AdmissionCertificate
        // embedded in a Proposal (cleared at round-finish in the advancer). The pre-existing
        // final liveness fallback below can still bypass probation if every eligible peer is in
        // probation; rc.7 does not alter that rc.6 emergency behavior, so deploy preflight must
        // reject such an anchor. Deterministic: derived from consensus-agreed lastOutcome.
        probationPeers = ReadmissionMaintenance.probationPeers(lastOutcome.readmissionCountdown)

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
                 s" [${lastOutcome.readmissionCountdown.map(kv => s"${kv._1.value.value.take(8)}:${kv._2}").mkString(",")}]"
               else "")
          )
          .whenA(probationPeers.nonEmpty)
        _ <- Metrics[F].updateGauge("dag_consensus_readmission_probation_size", probationPeers.size.toLong)
        _ <- Metrics[F].updateGauge(
          "dag_consensus_readmission_probation_ready_size",
          lastOutcome.readmissionCountdown.count { case (_, countdown) => countdown == 0 }.toLong
        )

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

        // Eligibility: penalty + probation are the only behavioural gates. The final allEligible fallback preserves rc.6's
        // last-resort probation bypass; it is safe only when anchor preflight proves a viable
        // non-probation first-round committee and therefore never reaches that rung.
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

        entropy = lastOutcome.finished.snapshotHash
        // No-certified-parent path (genesis): deterministic PeerId order within the configured cap, no hash input.
        selectedFacilitators = certifiedRoundProjection
          .map(_._1)
          .getOrElse(facilitatorSelector.getMaxCount.fold(eligibleThisRound.sorted)(cap => eligibleThisRound.sorted.take(cap)))
        expansionIntervalRounds = math.max(1, config.activeAdmissionExpansionIntervalRounds)
        membershipProjection = certifiedRoundProjection.fold(
          CertifiedRoundCommitteeProjector.project(
            key = key,
            selectedFacilitators = selectedFacilitators,
            config = config,
            // Buffered withdrawal rumors are not certified and can arrive before state creation on one node but after it on
            // another. The frozen round-start/QC committee therefore ignores that local timing input.
            withdrawnPeers = Set.empty
          )
        )(_._2)
        expansionAllowedThisRound = membershipProjection.expansionAllowed
        maxExpansionThisRound = membershipProjection.maxExpansionThisRound
        signingFacilitators = membershipProjection.votingCommittee
        _ <- ConsensusLog
          .info(
            logger,
            Facilitator,
            key.show,
            "n/a",
            FacilitatorSubsetting,
            "allEligible" -> allEligible.size.toString,
            "eligibleThisRound" -> eligibleThisRound.size.toString,
            "selected" -> selectedFacilitators.size.toString,
            "votingCommittee" -> signingFacilitators.size.toString,
            "expansionIntervalRounds" -> expansionIntervalRounds.toString,
            "expansionAllowed" -> expansionAllowedThisRound.toString
          )
          .whenA(selectedFacilitators.size < allEligible.size)
        _ <- Metrics[F].updateGauge("dag_consensus_active_facilitator_selected_size", selectedFacilitators.size.toLong)
        _ <- Metrics[F].updateGauge("dag_consensus_committee_size", signingFacilitators.size.toLong)

        time <- Clock[F].monotonic

        // Leader = round-robin over the frozen round committee (the same pool StateTransitions uses for VCC/TC view
        // changes), indexed by ordinal and view. No hash, quality, or leader-reported input can change who leads.
        leaderPool = signingFacilitators
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
        leader = LeaderSchedule.leader(leaderPool, key.value.value, initialView.toLong)

        _ <- ConsensusLog.info(
          logger,
          Facilitator,
          key.show,
          if (leader === selfId) "Leader" else "Validator",
          FacilitatorsFinalized,
          "eligible" -> allEligible.size.toString,
          "votingCommittee" -> signingFacilitators.size.toString,
          "leaderPool" -> leaderPool.size.toString,
          "excluded" -> (allEligible.size - eligibleThisRound.size).toString,
          "leader" -> ConsensusLog.pid(leader)
        )

        state = ConsensusState[GlobalSnapshotKey, GlobalSnapshotStatus, GlobalConsensusOutcome, GlobalConsensusKind](
          key,
          lastOutcome,
          Facilitators(signingFacilitators),
          // Canonical round-start committee -- same set as `facilitators` at creation,
          // but frozen for the lifetime of the round even when peers withdraw.
          Facilitators(signingFacilitators),
          CollectingFacilities(
            maybeTrigger,
            lastOutcome.finished.facilitatorsHash,
            lastOutcome.finished.snapshotHash
          ),
          time,
          withdrawnFacilitators = WithdrawnFacilitators.empty,
          eligibleFacilitators = EligibleFacilitators(allEligible),
          leader = leader,
          // Round-start view = certified initial view. MUST match the
          // `initialView` argument passed to LeaderSchedule above so the
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
            "facilitators" -> signingFacilitators.size.toString,
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
            (if (penalizedPeers.nonEmpty) Seq("penalized" -> penalizedPeers.size.toString) else Seq.empty) ++
              (if (probationPeers.nonEmpty) Seq("probation" -> probationPeers.size.toString) else Seq.empty) ++
              (if (abandonedMissing.nonEmpty) Seq("abandonedMissing" -> abandonedMissing.size.toString) else Seq.empty) ++
              (if (priorAbandonmentCount > 0) Seq("suppressedRetryViewSeed" -> priorAbandonmentCount.toString) else Seq.empty)
          ConsensusLog.info(logger, Lifecycle, key.show, role, RoundStarted, (basePairs ++ optionalPairs): _*)
        }

        // Complete dynamic reads and deterministic audit preparation before committing the state.
        // The retained effect runs the prepared membership action and exact Facility delivery only
        // after the round is committed, and may be replayed after failure or cancellation.
        assembledEvictionCertificates <- consensusStorage.getAssembledEvictionCertificates(key)
        _ <- GlobalSnapshotConsensusStateCreator
          .evictionVoteRetransmission(
            selfId,
            resources,
            signingFacilitators.toSet,
            signingFacilitators.toSet,
            assembledEvictionCertificates
          )
          .traverse_ { retransmission =>
            gossip.spreadDirect(
              ConsensusPeerEvictionVote(key, retransmission.vote),
              retransmission.recipients
            ) >>
              consensusQueue.offer(ConsensusCommand.CheckEvictionAssembly(key, retransmission.target)) >>
              Metrics[F].incrementCounter("dag_consensus_eviction_vote_retransmitted_total")
          }
        roundStartMembershipEffect <-
          if (membershipPolicy.allowsCertifiedAtomicReplacement)
            auditSigningFinalityParticipation(
              key,
              lastOutcome,
              signingFacilitators.toSet,
              signingFacilitators.toSet,
              resources,
              time,
              expansionAllowedThisRound
            ).handleErrorWith { error =>
              (logger.warn(error)(s"Signing finality-participation audit failed for key=$key; continuing round") >>
                Metrics[F].incrementCounter(
                  "dag_consensus_signing_finality_audit_total",
                  Seq(Metrics.unsafeLabelName("outcome") -> "error")
                )).as(Sync[F].unit)
            }
          else
            (finalityParticipationMissHistoryRef.set(FinalityParticipationAuditor.MissHistory.empty) >>
              Metrics[F].incrementCounter(
                "dag_consensus_signing_finality_audit_total",
                Seq(Metrics.unsafeLabelName("outcome") -> "disabled_by_membership_policy")
              )).as(Sync[F].unit)
        _ <- HasherSelector[F].withCurrent { implicit hasher =>
          DagAwaitingParentQueue.maintain(
            eventMempool,
            lastOutcome.finished.context,
            dagAwaitingParentConfig,
            maxAwaitingParentReactivationPerRound,
            logger
          )
        }
        _ <- DagAwaitingParentQueue.evictPermanentlyRejected(eventMempool, lastOutcome.finished.context, logger).void
        eventHashes <- eventMempool.getEventHashes
        // v19 phase 2: wall-clock millis at signing time. Raw, no bucketing; the
        // round-finalize median absorbs outliers and the consume-site clamp pins
        // monotonicity against the parent. See docs/consensus/view-from-time-anchor.md.
        proposerClockMs <- Clock[F].realTime.map(_.toMillis)
        triggerStatement <-
          HasherSelector[F].withCurrent { implicit hasher =>
            state.roundStartFacilitators.value.hash.flatMap { roundStartHash =>
              CertifiedConsensus.signTriggerStatement[F](
                CertifiedConsensus.triggerStatement(
                  CertifiedConsensus.ConsensusDomain.DagL0,
                  networkId,
                  key.value.value,
                  lastOutcome.finished.snapshotHash,
                  roundStartHash,
                  consensusConfigHash,
                  maybeTrigger
                ),
                keyPair
              )
            }
          }.map(_.some)
        facility = Facility(
          eventHashes,
          candidates,
          maybeTrigger,
          lastOutcome.finished.facilitatorsHash,
          lastOutcome.key,
          lastOutcome.finished.snapshotHash,
          consensusConfigHash = consensusConfigHash.some,
          proposerClockMs = proposerClockMs.some,
          triggerStatement = triggerStatement
        )
        declaration = ConsensusPeerDeclaration(key, facility)
        facilityEffect = ConsensusStateCreator.exactFacilityEffect[F, GlobalSnapshotKey](
          facility,
          declaration,
          signingFacilitators.toSet
        )(
          captured => consensusStorage.addFacility(selfId, key, captured).void,
          (captured, targets) => gossip.spreadDirect(captured, targets)
        )
        effect = roundStartMembershipEffect >> facilityEffect

      } yield (state, effect)
  }
}
