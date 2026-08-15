package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.MonadThrow
import cats.effect.Async
import cats.effect.kernel.{Clock, Ref, Sync}
import cats.effect.std.Queue
import cats.syntax.all._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.FiniteDuration

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
import io.constellationnetwork.node.shared.infrastructure.selfhealth.LocalHealthMonitor
import io.constellationnetwork.schema.ID.IdOps
import io.constellationnetwork.schema.mpt.GlobalStateKey
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{ControllerEvidenceEntry, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.{Hasher, HasherSelector}

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

  /** Canonical DAG bridge used exactly once at the v35 activation key.
    *
    * Kept as a package-visible pure-by-input helper so the activation regression can start from deliberately divergent legacy operational
    * windows and prove that both nodes derive the same parent outcome. The seed and its hash still come from the signed artifact; no local
    * sidecar field is accepted as an input.
    */
  private[snapshot] def resetLegacyOutcome[F[_]: MonadThrow: Hasher](
    key: GlobalSnapshotKey,
    outcome: GlobalConsensusOutcome,
    quorumThresholdFraction: Double
  ): F[GlobalConsensusOutcome] =
    ControllerEvidenceDerivation
      .certifiedActivationCommittee(outcome.finished.signedMajorityArtifact.value.peerHistory)
      .liftTo[F](
        new IllegalStateException(
          s"Cannot activate certified consensus at DAG ordinal=${key.value.value}: signed controller evidence is absent"
        )
      )
      .flatMap { seed =>
        val nextSeatQuorum = QuorumPolicy.fromFraction(seed.size + 1, quorumThresholdFraction)
        val activationViable =
          key.value.value == 0L ||
            (CommitteeViability.supportsCoordination(seed.size) &&
              CommitteeViability.canProveNextSeat(seed.size, quorumThresholdFraction))
        new IllegalStateException(
          s"Cannot activate certified consensus at DAG ordinal=${key.value.value}: canonical signed committee size=${seed.size}, " +
            s"minimum coordinated size=${CommitteeViability.MinimumCoordinatedCommitteeSize}, " +
            s"next-seat quorum=$nextSeatQuorum, quorumFraction=$quorumThresholdFraction"
        ).raiseError[F, Unit].unlessA(activationViable) >>
          seed.hash.map { seedHash =>
            outcome.copy(
              facilitators = Facilitators(seed),
              removedFacilitators = RemovedFacilitators.empty,
              withdrawnFacilitators = WithdrawnFacilitators.empty,
              eligibleFacilitators = EligibleFacilitators.empty,
              finished = outcome.finished.copy(candidates = Candidates.empty, facilitatorsHash = seedHash),
              removalPenalties = SortedMap.empty,
              deferralCountdown = SortedMap.empty,
              peerQuality = SortedMap.empty,
              cumulativeMissCounts = SortedMap.empty,
              recentProofSizes = SortedMap.empty,
              readmissionCountdown = SortedMap.empty,
              peerSelfHealth = SortedMap.empty,
              peerViewChanges = SortedMap.empty,
              recentSigners = SortedMap.empty,
              peerTiers = SortedMap.empty,
              activeAdmissionScores = SortedMap.empty,
              lastTimeoutCertificateVoters = scala.collection.immutable.SortedSet.empty,
              recentRoundEndTimes = SortedMap.empty,
              controllerEvidence = SortedMap.empty[SnapshotOrdinal, ControllerEvidenceEntry].some,
              penaltyUntil = SortedMap.empty[PeerId, SnapshotOrdinal].some
            )
          }
      }

  /** Preserve legacy self-fallback outside activation, but fail closed at the exact certified boundary. A singleton activation committee
    * can finalize yet can never satisfy next-seat headroom, while node-local self fallback would make different nodes freeze different
    * committees. Seedlist/collateral filtering must therefore leave at least the protocol-derived self-healing minimum.
    */
  private[snapshot] def finalizeEligibleCommitteeAtActivation(
    key: GlobalSnapshotKey,
    certifiedConsensusActivatesAtKey: Boolean,
    eligible: List[PeerId],
    selfId: PeerId,
    quorumThresholdFraction: Double
  ): Either[IllegalStateException, List[PeerId]] =
    validateActivationCommittee(
      key,
      certifiedConsensusActivatesAtKey,
      "seedlist/collateral eligible",
      eligible,
      quorumThresholdFraction
    ).map(_ => if (eligible.isEmpty) List(selfId) else eligible)

  /** Recheck the final selected/signing roster because max-facilitator-count and the committee projector run after eligibility validation.
    * A valid two-member input truncated to one at a non-genesis activation is still the same terminal fixed point.
    */
  private[snapshot] def validateActivationCommittee(
    key: GlobalSnapshotKey,
    certifiedConsensusActivatesAtKey: Boolean,
    stage: String,
    committee: Iterable[PeerId],
    quorumThresholdFraction: Double
  ): Either[IllegalStateException, Unit] = {
    val size = committee.size
    val nextSeatQuorum = QuorumPolicy.fromFraction(size + 1, quorumThresholdFraction)
    val activationViable =
      key.value.value == 0L ||
        (CommitteeViability.supportsCoordination(size) &&
          CommitteeViability.canProveNextSeat(size, quorumThresholdFraction))

    if (certifiedConsensusActivatesAtKey && !activationViable)
      Left(
        new IllegalStateException(
          s"Cannot activate certified consensus at DAG ordinal=${key.value.value}: $stage committee size=$size, " +
            s"minimum coordinated size=${CommitteeViability.MinimumCoordinatedCommitteeSize}, " +
            s"next-seat quorum=$nextSeatQuorum, quorumFraction=$quorumThresholdFraction; " +
            s"refusing local self fallback or admission fixed point"
        )
      )
    else Right(())
  }

  def make[F[_]: Async: Metrics: HasherSelector](
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
    coreCommitteeSize: Int,
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

    /** V35 activation bridge from a legacy outcome.
      *
      * The latest controller-evidence transition inside the signed DAG artifact's PeerHistory supplies the one source-of-truth committee.
      * The historical `nextFacilitators` field is a singleton compatibility value and must not be used here. Every controller/evidence
      * window that could have come from a node-local legacy sidecar is discarded at the exact activation key. The public artifact/context
      * bytes are untouched.
      */
    private def resetLegacyOutcomeAtActivation(
      key: GlobalSnapshotKey,
      outcome: GlobalConsensusOutcome
    ): F[GlobalConsensusOutcome] =
      if (!config.certifiedConsensusActivatesAt(key.value.value)) outcome.pure[F]
      else
        HasherSelector[F].withCurrent(implicit hasher => resetLegacyOutcome[F](key, outcome, consensusConfig.quorumThresholdFraction))

    /** Track every auditable parent-round signing-committee member from actual local snapshot proofs and audit one deterministic target.
      * Reuse the existing B1 vote path only after the round-count and elapsed-time miss floors, on the existing membership-change cadence,
      * and in the exact dead band where the current committee remains finalizable but one additional seat is unsupported.
      *
      * The local proof subset, monotonic time, and resulting headroom decision are evidence for local vote emission only. They never enter
      * the outcome, artifact, committee hash, or state proof. The Core-quorum EvictionCertificate has no standalone removal authority on
      * this path: it must be paired one-for-one with a Core-quorum open ReadyAtTip AdmissionCertificate in the certified ProposalValue.
      */
    private def auditSigningFinalityParticipation(
      key: GlobalSnapshotKey,
      lastOutcome: GlobalConsensusOutcome,
      currentCore: Set[PeerId],
      currentSigningCommittee: Set[PeerId],
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind],
      observedAt: FiniteDuration,
      cadenceAllowed: Boolean
    ): F[Unit] = {
      val parentEvidence = lastOutcome.controllerEvidence.flatMap(_.get(lastOutcome.key))
      val locallyObservedParentSigners =
        lastOutcome.finished.signedMajorityArtifact.proofs.toList.map(_.id.toPeerId).toSet
      val inBootstrap =
        !lastOutcome.recentProofSizes.values.exists(_ >= consensusConfig.bootstrapCompleteProofsThreshold)
      val outcomeLabel = Metrics.unsafeLabelName("outcome")

      parentEvidence match {
        case None =>
          // Recovery from a snapshot whose one-round operational sidecar is unavailable can
          // temporarily lack the exact parent round-start committee. Clear local streaks and
          // skip rather than bridging an unknown round into a false consecutive-miss sequence.
          finalityParticipationMissHistoryRef.set(FinalityParticipationAuditor.MissHistory.empty) >>
            Metrics[F]
              .incrementCounter(
                "dag_consensus_signing_finality_audit_total",
                Seq(outcomeLabel -> "missing_parent_evidence")
              )
              .whenA(!inBootstrap && currentCore.contains(selfId))

        case Some(evidence) =>
          val currentCommittee = currentSigningCommittee
          val finalityHeadroom = FinalityHeadroom.evaluate(
            currentCommittee,
            locallyObservedParentSigners,
            consensusConfig.quorumThresholdFraction
          )
          val requiredMissDuration = FinalityParticipationAuditor.minimumMissDuration(
            consensusConfig.timeTriggerInterval,
            TierTransitions.DemotionConsecutiveMisses
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
              evidence.roundStartFacilitators.toSet,
              locallyObservedParentSigners,
              lastOutcome.key.value.value,
              lastOutcome.finished.snapshotHash,
              observedAt,
              requiredMissDuration,
              inBootstrap,
              previous
            )
            (observation.history, observation.decision)
          }.flatMap {
            case Some(audit) if FinalityParticipationAuditor.shouldEmitAtomicReplacementVote(audit, finalityHeadroom, cadenceAllowed) =>
              val alreadyVoted = resources.evictionVotes.get(audit.target).exists(_.contains(selfId))
              val emit =
                if (alreadyVoted) Sync[F].unit
                else evictionVoter.emitEvictionVote(key, audit.target, EvictionReason.Silent)

              emit >>
                // Queue assembly before the first Facility is sent. Every honest Core voter
                // audits the same target; local proof differences can only cause abstention.
                consensusQueue.offer(ConsensusCommand.CheckEvictionAssembly(key, audit.target)) >>
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

            case Some(audit) if audit.signatureObserved =>
              Metrics[F].incrementCounter(
                "dag_consensus_signing_finality_audit_total",
                Seq(outcomeLabel -> "signature_observed")
              )

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
                Metrics[F].incrementCounter(
                  "dag_consensus_signing_finality_audit_total",
                  Seq(outcomeLabel -> outcome)
                )

            case None => Sync[F].unit
          }
      }
    }

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
      providedLastOutcome: GlobalConsensusOutcome,
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind],
      priorAbandonmentCount: Int
    ): F[(GlobalSnapshotConsensusState, F[Unit])] =
      for {
        lastOutcome <- resetLegacyOutcomeAtActivation(key, providedLastOutcome)
        candidates <- consensusStorage.getCandidates(key.next)
        previousEligible = lastOutcome.eligibleOrFacilitators
        approvedCandidates = lastOutcome.finished.candidates.value
        seedlistPeerIds = seedlist.fold(List.empty[PeerId])(_.toList.map(_.peerId))
        certifiedNextRoundValue = lastOutcome.finished.certifiedOutcome.map(_.proposalQc.value)
        certifiedRoundProjection <- certifiedNextRoundValue.traverse { value =>
          CertifiedRoundCommitteeProjector
            .fromCertifiedParent[F](
              key = key,
              parentValue = value,
              parentRecentSigners = lastOutcome.recentSigners,
              parentControllerEvidence = lastOutcome.controllerEvidence.getOrElse(SortedMap.empty),
              parentCarried = CertifiedRoundCommitteeProjector.CarriedControllerState(
                activeScores = lastOutcome.activeAdmissionScores.toMap,
                peerQuality = lastOutcome.peerQuality.toMap,
                peerTiers = lastOutcome.peerTiers,
                viewChanges = lastOutcome.peerViewChanges.toMap,
                selfHealth = lastOutcome.peerSelfHealth.toMap
              ),
              config = config,
              coreCommitteeSize = coreCommitteeSize,
              seedlistPeerIds = seedlistPeerIds.toSet,
              isContextEligible = consensusFns.facilitatorEligible(lastOutcome.finished.context, _),
              facilitatorSelector = facilitatorSelector,
              parentArtifactHash = lastOutcome.finished.snapshotHash
            )
            .flatMap(
              _.leftMap(error => new IllegalStateException(s"Certified round projection failed for key=$key: $error"))
                .liftTo[F]
            )
        }

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
        // A bridge node may start from an rc.6 anchor that already carries health-derived
        // `removedFacilitators`. Retain mode must neutralize those inherited removals too;
        // otherwise the first rc.7 round would contract before the new emission/apply gates
        // get a chance to take effect. Currency keeps consuming the carried set unchanged.
        carriedFacilityRemovals = membershipPolicy.persistentFacilityRemovals(lastOutcome.removedFacilitators.value)
        lastSigners = lastFacilitators -- carriedFacilityRemovals
        tcaDegraded <- tcaFilter.degradedPeers(lastFacilitators, lastSigners)
        tcaFilteredBase = tcaDegraded match {
          case Some(degraded) =>
            val filtered = fullBase.filterNot(degraded.contains)
            if (filtered.isEmpty) fullBase
            else filtered
          case None => fullBase
        }

        _ <- tcaDegraded.traverse_ { degraded =>
          ConsensusLog.debug(
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
        collateralEligible <- tcaFilteredBase
          .filterA(
            consensusFns.facilitatorFilter(
              lastOutcome.finished.signedMajorityArtifact,
              lastOutcome.finished.context,
              _
            )
          )
        allEligible <- finalizeEligibleCommitteeAtActivation(
          key,
          config.certifiedConsensusActivatesAt(key.value.value),
          collateralEligible,
          selfId,
          config.quorumThresholdFraction
        ).liftTo[F]

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
        // sign and earn but do not count toward the Core liveness quorum, so chronic peers cannot
        // wedge leader/VC certificate progress by being absent. The separate frozen-committee
        // finality floor remains unchanged. The final allEligible fallback preserves rc.6's
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

        // Apply deterministic subset selection using hash-distance ordering
        // Uses the previous round's snapshot hash as entropy for randomization
        entropy = lastOutcome.finished.snapshotHash
        selectedFacilitators = certifiedRoundProjection
          .map(_.nextRound.selectedCommittee)
          .getOrElse(facilitatorSelector.select(eligibleThisRound, entropy))
        expansionIntervalRounds = math.max(1, config.activeAdmissionExpansionIntervalRounds)
        locallyBufferedWithdrawals = selectedFacilitators.iterator
          .filter(peerId => resources.withdrawalsMap.get(peerId).contains(GlobalConsensusKind.Facility))
          .toSet
        withdrawnPeers = CertifiedRoundCommitteeProjector.roundStartWithdrawals(
          config.certifiedConsensusActiveAt(key.value.value),
          locallyBufferedWithdrawals
        )
        membershipProjection = certifiedRoundProjection.fold(
          CertifiedRoundCommitteeProjector.project(
            key = key,
            selectedFacilitators = selectedFacilitators,
            recentSigners = lastOutcome.recentSigners,
            controllerEvidence = lastOutcome.controllerEvidence.getOrElse(SortedMap.empty),
            carried = CertifiedRoundCommitteeProjector.CarriedControllerState(
              activeScores = lastOutcome.activeAdmissionScores.toMap,
              peerQuality = lastOutcome.peerQuality.toMap,
              peerTiers = lastOutcome.peerTiers,
              viewChanges = lastOutcome.peerViewChanges.toMap,
              selfHealth = lastOutcome.peerSelfHealth.toMap
            ),
            config = config,
            coreCommitteeSize = coreCommitteeSize,
            forcedTier1Peers = Set.empty,
            withdrawnPeers = withdrawnPeers
          )
        )(_.committee)
        admissionSizing = membershipProjection.admissionSizing
        expansionAllowedThisRound = membershipProjection.expansionAllowed
        maxExpansionThisRound = membershipProjection.maxExpansionThisRound
        controllerInputs = membershipProjection.controllerInputs
        activeAdmission = membershipProjection.activeAdmission
        signingMembership = membershipProjection.signingMembership
        activeFacilitators = signingMembership.retained
        committees = membershipProjection.committees
        signingFacilitators = membershipProjection.signingFacilitators
        _ <- validateActivationCommittee(
          key,
          config.certifiedConsensusActivatesAt(key.value.value),
          "final selected/signing",
          signingFacilitators,
          config.quorumThresholdFraction
        ).liftTo[F]
        _ <- logger.debug(
          s"Controller inputs for key=$key: " + (
            if (controllerInputs.evidenceRounds === 0) "controller_evidence=empty fallback=carried"
            else s"controller_evidence=${controllerInputs.evidenceRounds} rounds"
          )
        )
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
            "active" -> activeFacilitators.size.toString,
            "coreEligible" -> activeAdmission.active.size.toString,
            "retainedAsTier1" -> signingMembership.nonCore.size.toString,
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
            "recentSignerWindow" -> activeAdmission.recentWindowSize.toString,
            "recentSignerMinCount" -> activeAdmission.recentSignerMinCount.toString,
            "recentSignerMaxCount" -> activeAdmission.recentSignerMaxCount.toString,
            "expansionIntervalRounds" -> expansionIntervalRounds.toString,
            "expansionAllowed" -> expansionAllowedThisRound.toString,
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
        _ <- Metrics[F].updateGauge("dag_consensus_active_facilitator_core_eligible_size", activeAdmission.active.size.toLong)
        _ <- Metrics[F].updateGauge("dag_consensus_active_facilitator_tier1_retained_size", signingMembership.nonCore.size.toLong)
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
        _ <- Metrics[F].updateGauge("dag_consensus_active_facilitator_recent_signer_min_count", activeAdmission.recentSignerMinCount.toLong)
        _ <- Metrics[F].updateGauge("dag_consensus_active_facilitator_recent_signer_max_count", activeAdmission.recentSignerMaxCount.toLong)
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

        withdrawn = activeFacilitators.filter(withdrawnPeers.contains)

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
          assembledEvictionCertificates <- consensusStorage.getAssembledEvictionCertificates(key)
          _ <- GlobalSnapshotConsensusStateCreator
            .evictionVoteRetransmission(
              selfId,
              resources,
              committees.core.toSet,
              committees.core.toSet ++ committees.tier1.toSet,
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
          _ <-
            if (membershipPolicy.allowsCertifiedAtomicReplacement(config.certifiedConsensusActiveAt(key.value.value)))
              auditSigningFinalityParticipation(
                key,
                lastOutcome,
                committees.core.toSet,
                signingFacilitators.toSet,
                resources,
                time,
                expansionAllowedThisRound
              ).handleErrorWith { error =>
                logger.warn(error)(s"Signing finality-participation audit failed for key=$key; continuing round") >>
                  Metrics[F].incrementCounter(
                    "dag_consensus_signing_finality_audit_total",
                    Seq(Metrics.unsafeLabelName("outcome") -> "error")
                  )
              }
            else
              finalityParticipationMissHistoryRef.set(FinalityParticipationAuditor.MissHistory.empty) >>
                Metrics[F].incrementCounter(
                  "dag_consensus_signing_finality_audit_total",
                  Seq(Metrics.unsafeLabelName("outcome") -> "disabled_by_membership_policy")
                )
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
          _ <- gossip.spreadDirect(declaration, signingFacilitators.toSet)
        } yield ()

        // v19 multi-committee derivation. Partition `active` into Core / Tier 1 / Witness
        // using the stage-4 controller inputs (evidence-derived tiers + quality when the
        // signed controllerEvidence window has entries; the carried-forward lastOutcome maps
        // only in the empty-window fallback). Tier assignment rule (re-derived every round):
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
        // Chronic-core replacement ladder: `chronicMisses` (evidence-derived trailing miss
        // streaks, empty in the fallback regime) bars chronically-missing peers from Core,
        // swaps them for the highest-scored non-chronic reserves, prefers a smaller Core over
        // chronic padding when supply is short, and re-admits the least-bad chronic peers only
        // below MinViableCoreSize. See the CommitteeBuilder scaladoc for the full ladder.
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
          qualityScores = controllerInputs.peerQuality,
          selfHealthHints = controllerInputs.selfHealth,
          peerViewChanges = controllerInputs.viewChanges,
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
          "active" -> signingFacilitators.size.toString,
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
          withdrawnFacilitators = WithdrawnFacilitators(withdrawn.toSet),
          eligibleFacilitators = EligibleFacilitators(allEligible),
          coreFacilitators = CoreFacilitators(committees.core),
          tier1Facilitators = Tier1Facilitators(committees.tier1),
          certifiedConsensusActive = config.certifiedConsensusActiveAt(key.value.value),
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
