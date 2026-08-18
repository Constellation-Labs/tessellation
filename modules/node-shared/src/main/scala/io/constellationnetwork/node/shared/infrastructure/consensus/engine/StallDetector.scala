package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.Order
import cats.effect.kernel._
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event => LogEvent}
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{AdmissionReason, EvictionReason, EvictionVote}
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.gossip.event.ChainTip
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.ID.IdOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.{PeerId, PeerResponsiveness, Unresponsive}
import io.constellationnetwork.security.HasherSelector
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._

/** Monitors a consensus round for stalls and manages recovery.
  *
  * ==Architecture==
  *
  * StallDetector is the orchestrator that polls state periodically and delegates to focused components:
  *   - '''ViewChangeManager''': deterministic leader re-election on proposal stalls
  *   - '''AbandonmentTracker''': consecutive failure tracking, resource cleanup, recovery download
  *   - '''ConsensusHealthStatus''': observable health snapshot for HTTP endpoint + metrics
  *
  * ==Stall Detection Flow==
  * {{{
  *   Poll (100ms-1000ms adaptive)
  *     → Detect status/resource changes → queue CheckUpdate
  *     → Calculate phase-adaptive timeout
  *     → If leader unresponsive → early view change (ViewChangeManager)
  *     → If timeout exceeded:
  *         → Proposal phase: view change (ViewChangeManager)
  *         → Other phases: count toward abandon
  *     → After maxStallCycles or maxRoundDuration → abandon (AbandonmentTracker)
  *     → Update health snapshot on each cycle
  * }}}
  */
@scala.annotation.nowarn("msg=type parameter Outcome.*shadows")
class StallDetector[F[_]: Async: HasherSelector: Metrics, Event, Key: Order, Artifact, Ctx, Status, Outcome, Kind](
  ctx: ConsensusEngineContext[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind],
  viewChangeManager: ViewChangeManager[F, Key, Artifact, Ctx, Status, Outcome, Kind],
  abandonmentTracker: AbandonmentTracker[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind],
  evictionVoter: EvictionVoter[F, Key],
  admissionVoter: AdmissionVoter[F, Key],
  probationPeersOf: Outcome => Set[PeerId],
  admissionNomineesOf: Outcome => Set[PeerId],
  parentRoundCommitteeOf: Outcome => Set[PeerId],
  openAdmissionCadenceOf: Key => Boolean,
  locallyObservedParentSignersOf: Outcome => Option[Set[PeerId]],
  lastSnapshotHashOf: Outcome => Hash,
  getPeerChainTips: F[Map[PeerId, ChainTip]],
  admissionCandidateTipProbe: Option[AdmissionCandidateTipProbe.Probes[F]],
  healthRef: Ref[F, ConsensusHealthStatus],
  // Local-only: consecutive-observation streaks of "probation peer at committed tip" for B2
  // stability gating. Not part of consensus-agreed state — two honest nodes may have different
  // streak counts for the same peer depending on local tick timing. That is fine: the actual
  // re-admission is controlled by the quorum-certified AdmissionCertificate, so local streak
  // disagreement just shifts when a given node emits its vote. Both honest nodes will eventually
  // emit once their local stability threshold is met, and certificate assembly requires a
  // majority of signed votes to agree, so streak drift across honest nodes only delays
  // re-admission; it cannot cause divergent outcomes.
  b2AtTipStreakRef: Ref[F, Map[PeerId, Int]],
  // Actual finalized parent proof subsets are node-local and therefore live outside Outcome.
  // This bounded history controls vote emission only; AdmissionCertificate validation and apply
  // remain independent of it.
  admissionProofHistoryRef: Ref[F, AdmissionProofHistory.History]
) {

  import ctx.{clusterStorage, config, logger, ops, peerQualityTracker, queue, selfId, storage}

  private def selfRole(state: ConsensusState[Key, Status, Outcome, Kind]): String =
    ConsensusLog.role(selfId, state.leader)

  private case class MonitorState(
    lastResourcesHash: Int,
    lastStatus: Option[Status],
    statusStartTime: FiniteDuration,
    roundStartTime: FiniteDuration,
    noChangeCount: Int,
    stallCount: Int,
    lastSummaryTime: FiniteDuration,
    lastScoreLogTime: FiniteDuration,
    // Bounded Facility retransmit attempt counter (0-indexed). Incremented each time we re-broadcast
    // our own stored Facility while in CollectingFacilities. Capped by MaxFacilityRetransmits to
    // avoid spam when the round is genuinely stuck. Sender-side mitigation for gossip delivery
    // asymmetry -- peers that missed our original Facility via plain `spread` get it via direct push
    // on retry. The delay between successive retransmits follows a capped
    // exponential backoff rather than a fixed cadence (see StallDetector.nextRetransmitDelay).
    retransmitAttempt: Int = 0,
    // Wall-clock of the last retransmit, used to gate the next attempt against the exponential
    // backoff schedule. None until the first retransmit fires.
    lastRetransmitAt: Option[FiniteDuration] = None,
    // Tracks the view number at which roundStartTime was most recently set. Used to reset the
    // round-duration clock when the view advances, so the maxRoundDuration safety net applies
    // per-view, not to the entire life of the round. Without this, a round that view-changes
    // late in the 300s window runs out of budget in view-1 CollectingSignatures even though
    // the new view is making steady progress -- observed in fork-recovery E2E.
    lastView: Int = 0,
    // Local duplicate-suppression for timestamp-driven ViewChangeVote emission. The vote still
    // goes through the normal signed VCV/VCC path; this only avoids re-signing the same
    // current->next view transition on every monitor tick.
    lastTimestampPacemakerVoteView: Option[Int] = None,
    // Open-nominee probes are single-shot during one continuous monitor attempt. Probation
    // recovery deliberately re-probes one fixed target at a locally rate-limited cadence until
    // its short stability streak completes; those targets are never added to this open-lane set.
    admissionTipProbedTargets: Set[PeerId] = Set.empty,
    // Monotonic time of the last launched probation probe. This is local transport throttling,
    // not consensus input. A skipped tick preserves streak history but cannot authorize a vote.
    lastProbationTipProbeAt: Option[FiniteDuration] = None
  )

  private case class AdmissionVoteEmission(
    openProbedTargets: Set[PeerId],
    probationObservation: AdmissionCandidateTipProbe.Observation
  )

  private val basePollInterval = 100L
  private val maxPollInterval = AdmissionCandidateTipProbe.MinimumProbationProbeInterval.toMillis

  // Facility-retransmit tunables and the backoff schedule live on the companion object
  // (StallDetector.MaxFacilityRetransmits etc.) so they are unit-testable as pure values
  // independent of the class instance.

  /** Minimum round-elapsed time before an EARLY_VIEW_CHANGE may fire on an Unresponsive leader. Guards against the interaction between
    * eager Unresponsive marking in LocalHealthcheck (set on the first failed gossip probe, well before the healthcheck can confirm) and the
    * proposal-phase leader-liveness check here. 5 seconds is long enough for healthcheck to either confirm genuinely-unresponsive leaders
    * or clear false positives from transient bootstrap gossip failures, and short enough that a truly dead leader still triggers view
    * change within the normal declarationTimeout window.
    */
  private val EarlyViewChangeMinRoundElapsed: FiniteDuration = 5.seconds

  private case class ResourcesInfo(hash: Int, declaredCount: Int, activeCount: Int, missingPeerIds: Set[String], missingPeers: Set[PeerId])

  def monitor(key: Key, cancelSignal: Deferred[F, Unit]): F[Unit] =
    for {
      now <- Async[F].monotonic
      _ <- Async[F].race(
        cancelSignal.get,
        Async[F].tailRecM(
          MonitorState(
            lastResourcesHash = 0,
            lastStatus = None,
            statusStartTime = now,
            roundStartTime = now,
            noChangeCount = 0,
            stallCount = 0,
            lastSummaryTime = now,
            lastScoreLogTime = now,
            retransmitAttempt = 0,
            lastRetransmitAt = None
          )
        )(monitorStep(key, _))
      )
    } yield ()

  private def monitorStep(key: Key, ms: MonitorState): F[Either[MonitorState, Unit]] =
    // Snapshot the attempt id before reading state. Any mutation before, during, or after
    // the state read then changes the id seen by the serialized command-loop drain, which
    // safely rejects this monitor cycle's stale decision. Reading the id after state could
    // bind a stale phase-0 decision to the newer phase-1 attempt.
    (storage.getRoundAttemptId, storage.getResourceGeneration(key)).tupled.flatMap {
      case (observedAttemptId, observedResourceGeneration) =>
        storage.getState(key).flatMap {
          case None =>
            ConsensusLog.debug(logger, Category.Lifecycle, key.toString, "n/a", LogEvent.MonitorStateGone) >>
              healthRef.update(_.copy(isRunning = false, key = None, phase = None)) >>
              Async[F].pure(Right(()))

          case Some(state) =>
            ctx.advancer.getConsensusOutcome(state) match {
              case Some(_) =>
                ConsensusLog.debug(logger, Category.Lifecycle, key.toString, "n/a", LogEvent.MonitorOutcomeReady) >>
                  Async[F].pure(Right(()))

              case None =>
                runMonitorCycle(key, ms, state, observedAttemptId, observedResourceGeneration)
            }
        }
    }

  /** Core monitoring cycle: detect changes, check timeouts, handle stalls, update health. */
  private def runMonitorCycle(
    key: Key,
    ms: MonitorState,
    state: ConsensusState[Key, Status, Outcome, Kind],
    observedAttemptId: Long,
    observedResourceGeneration: Long
  ): F[Either[MonitorState, Unit]] =
    for {
      now <- Async[F].monotonic
      resources <- storage.getResources(key)
      observedPacemakerEpoch = ViewChangeManager.ObservedEpoch(
        state.viewNumber.toLong,
        observedAttemptId,
        observedResourceGeneration
      )

      // v33 quorum-denominator shrink: one decision per monitor cycle, shared by the
      // feasibility gates below. Derived ONLY from consensus-agreed anchors + wall clock
      // (see QuorumDenominatorShrink scaladoc); inert in normal operation.
      shrinkDecision <- ctx.advancer.quorumShrinkDecision(state)
      // v4.1.0 cluster-majority floor: separate committee-floored decision whose `baseQuorum` is the actual
      // finality floor outside bootstrap. Used ONLY by the terminal halt diagnostic below; the abandon/
      // eviction feasibility keeps using the Core-sized `shrinkDecision`.
      finalityDecision <- ctx.advancer.quorumFinalityDecision(state)
      _ <- Metrics[F].updateGauge("dag_consensus_quorum_shrink_active", if (shrinkDecision.active) 1L else 0L)
      _ <- Metrics[F]
        .updateGauge("dag_consensus_quorum_shrink_required", shrinkDecision.requiredQuorum.toLong)
        .whenA(shrinkDecision.active)

      info = getResourcesInfo(state, resources)
      statusChanged = !ms.lastStatus.contains(state.status)
      resourcesChanged = info.hash != ms.lastResourcesHash

      // Reset round-duration clock when the view advances. The per-view deadline scales with
      // the view number so later views (which run under worse conditions) get more slack,
      // capped to prevent runaway. See maxRoundDurationForView(view) below.
      viewAdvanced = state.viewNumber > ms.lastView
      newRoundStartTime = if (viewAdvanced) now else ms.roundStartTime

      newStatusStartTime = if (statusChanged) now else ms.statusStartTime
      statusDuration = now - newStatusStartTime
      newStallCount = if (statusChanged) 0 else ms.stallCount

      // Heartbeat the advancer while the round is in a signatures-collecting phase
      // (MajoritySignature + currency-l0 BinarySignature). These phases use the
      // advancer's signatureGracePeriod gate (43a154519), which returns
      // `none[Transition]` when quorum is met but the committee isn't full yet,
      // hoping another signer shows up. The advancer has no direct mechanism to
      // re-enter `toFinishedPhase` after the grace window elapses — it only gets
      // re-triggered by CheckUpdate when resources or status change. If no further
      // signatures arrive (common — the straggler is offline/slow), the round
      // wedges until some unrelated rumor fires resourcesChanged seconds or tens
      // of seconds later. This heartbeat ticks CheckUpdate every monitor cycle
      // (100-1000ms) while in the at-risk phase so the grace check re-evaluates
      // naturally; checkUpdate is a no-op on unchanged state, so the overhead is
      // trivial. Observed in E2E: single round wedged 14.7s without this.
      // B2 admission emission: when a peer currently in `readmissionCountdown` has a
      // matching chain tip in the mesh-gossip table, emit an `AdmissionVote` for them.
      //
      // Evaluates on every poll tick when probation is non-empty (not just on
      // resourcesChanged). Direct probation transport is rate-limited below to one launched
      // request per max poll interval. Witness signal is the mesh chain tip, which updates
      // independently of per-round consensus resources — binding emission to
      // `resourcesChanged` misses the case where a probation peer's mesh tip advances
      // mid-round without any consensus declaration arriving. Observed in E2E:
      // only 1 voter per cycle produced an AdmissionVote, so the 3-of-5 quorum never
      // assembled and re-admission fell back to countdown expiry. With independent periodic
      // evaluation, every committee member gets a chance to observe the probation peer at tip within
      // the 3-round window, tightening the cert-gated path into the primary admission
      // route rather than a rare opportunistic one.
      //
      // Safety against spam: `GossipingAdmissionVoter` calls `storage.addAdmissionVote`
      // with first-write-wins semantics per (voter, target). Direct probation requests are
      // limited to one fixed target per second, and skipped ticks cannot emit from a carried
      // streak. Any later fresh re-emission is still a storage no-op for that voter/target.
      probationProbeDue = AdmissionCandidateTipProbe.isProbeDue(
        ms.lastProbationTipProbeAt,
        now,
        maxPollInterval.millis
      )
      roundStartFacilitatorsHash <- HasherSelector[F].withCurrent(implicit hasher => state.roundStartFacilitators.value.hash)
      admissionVoteEmission <- maybeEmitAdmissionVotes(
        key,
        state,
        resources,
        roundStartFacilitatorsHash,
        ms.admissionTipProbedTargets,
        probationProbeDue
      )

      inFacilitiesPhase = ops.phaseIndex(state.status) == 0
      inSignaturesPhase = ops.isSignaturesPhase(state.status)
      _ <- queue
        .offer(ConsensusCommand.CheckUpdate(key))
        .whenA(resourcesChanged || statusChanged || inFacilitiesPhase || inSignaturesPhase)

      // --- Timeout calculation ---
      effectiveTimeout <- calculateTimeout(ms.stallCount, info, state)

      // --- Timestamp pacemaker ---
      // The parent outcome's consensus end-time is signed outcome data. Use it as timeout
      // evidence only: emit a VCV when the current view is overdue, then let VCC assembly
      // decide whether the view actually advances.
      timestampPacemakerFired <- maybeEmitTimestampPacemakerVote(
        key,
        state,
        ms.lastTimestampPacemakerVoteView,
        observedPacemakerEpoch
      )

      // --- Early view change for unresponsive leader ---
      // Gate on a minimum round-elapsed window so a stale Unresponsive flag carried
      // over from bootstrap gossip failures (now eagerly set by LocalHealthcheck) does
      // not trigger an instant view change before the leader has had any real chance
      // to respond. Without this, a healthy leader that happened to miss a single
      // gossip probe 100ms earlier can be demoted before its declarations arrive —
      // observed in fork-recovery E2E at 508ms after round start. With this gate, the
      // healthcheck recovery has time to clear false positives before the check fires.
      roundElapsedForViewChange = now - ms.roundStartTime
      leaderUnresponsive <- isLeaderUnresponsive(state.leader)
      earlyViewChangeDue = leaderUnresponsive &&
        ops.isProposalPhase(state.status) &&
        ms.stallCount == 0 &&
        roundElapsedForViewChange >= EarlyViewChangeMinRoundElapsed
      // If the timestamp pacemaker already emitted a VCV for this view on this tick, do not
      // emit a second identical leader-unresponsive VCV. The duplicate should be harmless at
      // storage level, but it pollutes VCV/VCC telemetry and wastes gossip.
      earlyViewChangeRequested = earlyViewChangeDue && !timestampPacemakerFired
      earlyViewChangeFired <-
        if (earlyViewChangeRequested)
          viewChangeManager.performViewChange(key, observedPacemakerEpoch).flatTap { emitted =>
            ConsensusLog
              .warn(
                logger,
                Category.Stall,
                key.toString,
                selfRole(state),
                LogEvent.EarlyViewChange,
                "leader" -> ConsensusLog.pid(state.leader),
                "reason" -> "leader_unresponsive",
                "roundElapsedMs" -> roundElapsedForViewChange.toMillis.toString
              )
              .whenA(emitted)
          }
        else false.pure[F]

      // Self-recovered-leader cooldown removed (alpha.96). Was a local-only check here: if
      // this node had just completed initFromDownload and got elected leader within
      // `recoveryLeaderCooldownRounds`, it self-yielded via performViewChange to avoid a
      // ~98s wedge while its mesh/storage primed. In production that local self-yield broke
      // committee symmetry: the recently-recovered peer would advance its view while peers
      // that came up via a different path stayed on the natural view, producing a leader
      // split-brain where both sides treated themselves as leader, signed different
      // artifacts, and rejected each other's signatures as invalid (testnet 2026-05-21
      // alpha.95 wedge at ord 3127144, signatures stuck 1/2 across many minutes). The
      // recoveredAtKeyRef set in StateTransitions.scala still records recovery completion
      // but is no longer consulted here; a deterministic-across-committee re-introduction
      // would require putting the marker on-chain (schema change), which is out of scope
      // for this patch. The natural stall-detector view-change in handleStall below still
      // rotates a wedged leader, just on the normal ~30s declarationTimeout cadence instead
      // of the aggressive ~5s cooldown rotation.

      // --- Handle stall: view change for proposal phase, count toward abandon for others ---
      stallResult <-
        if (earlyViewChangeFired || timestampPacemakerFired)
          StallResult(
            didStall = true,
            quorumInfeasible = false,
            pacemakerRequestEnqueued = true
          ).pure[F]
        else
          handleStall(
            key = key,
            state = state,
            declarationTimeout = effectiveTimeout,
            statusDuration = statusDuration,
            declaredCount = info.declaredCount,
            activeCount = info.activeCount,
            missingPeers = info.missingPeers,
            stallCount = newStallCount,
            quorumOverride = shrinkDecision.quorumOverride,
            observedPacemakerEpoch = observedPacemakerEpoch
          )

      didStall = stallResult.didStall
      adjustedStatusStartTime = if (didStall) now else newStatusStartTime
      finalStallCount = if (didStall) newStallCount + 1 else newStallCount

      // v4.1.0 terminal halt diagnostic (observability only): announce when a stalled round's committee
      // cannot reach the finality floor (cluster too degraded -- silent/flaky peers -- to finalize under
      // the safety floor). Does not change any abandon/eviction decision; see announceHaltIfDegraded.
      _ <- announceHaltIfDegraded(key, state, info.missingPeers, finalStallCount, finalityDecision.baseQuorum)
        .whenA(didStall)

      // Bounded sender-side retransmit of our own Facility when stalled in CollectingFacilities.
      // Switched from "fire on every stall cycle" to capped exponential backoff.
      //   Previously: retransmit fired on each `didStall` (~30s cadence determined by declarationTimeout)
      //            so 3 retransmits took ~90s; the E2E ord-6 stall ate ~3 minutes because
      //            gossip-mesh-dropped Facility decls weren't pushed back into the network fast
      //            enough during the high-jitter cold-start window.
      //   Now: retransmit fires when `(now - lastRetransmitAt) >= nextRetransmitDelay(attempt)`,
      //        producing schedule 5s -> 10s -> 20s -> 30s -> 30s. First three attempts within ~35s,
      //        bounded steady-state at 30s for the remaining attempts; cap is now 5 (raised from 3)
      //        to keep the same ~95s total budget while landing the early attempts much sooner.
      // Gates retained (all still required for retransmit to fire):
      //   - CollectingFacilities phase (phase index 0),
      //   - quorum still feasible (don't waste push bandwidth if round is doomed),
      //   - self is an active (non-withdrawn) facilitator for this round,
      //   - haven't passed MaxFacilityRetransmits for this round,
      //   - local progress is still below the facilitator count.
      // The retransmit reads the stored self-Facility and re-sends it via the same direct-push
      // path used for Proposal/Signature. It does NOT rebuild the declaration — no recomputation
      // drift, preserves the original `eventHashes`/`candidates`/`trigger`.
      isFacilitiesPhase = ops.phaseIndex(state.status) == 0
      selfIsActiveFacilitator = state.facilitators.value.contains(ctx.selfId) &&
        !state.withdrawnFacilitators.value.contains(ctx.selfId)
      belowCap = ms.retransmitAttempt < StallDetector.MaxFacilityRetransmits
      needsMore = info.declaredCount < info.activeCount
      // First retransmit fires once `FacilityRetransmitInitialDelay` has elapsed since round start.
      // Subsequent retransmits gate on the per-attempt backoff schedule.
      retransmitDue = ms.lastRetransmitAt.fold(
        (now - ms.roundStartTime) >= StallDetector.FacilityRetransmitInitialDelay
      ) { last =>
        (now - last) >= StallDetector.nextRetransmitDelay(ms.retransmitAttempt)
      }
      shouldRetransmit = retransmitDue && isFacilitiesPhase && !stallResult.quorumInfeasible &&
        selfIsActiveFacilitator && belowCap && needsMore
      activeFacilitatorTargets = state.facilitators.value.toSet -- state.withdrawnFacilitators.value - ctx.selfId
      retransmitFired = shouldRetransmit && activeFacilitatorTargets.nonEmpty
      _ <- ctx.creator
        .retransmitOwnFacility(key, activeFacilitatorTargets)
        .whenA(retransmitFired)
      newRetransmitAttempt = if (retransmitFired) ms.retransmitAttempt + 1 else ms.retransmitAttempt
      newLastRetransmitAt = if (retransmitFired) Some(now) else ms.lastRetransmitAt

      _ <- Metrics[F].updateGauge("dag_consensus_stall_cycle", finalStallCount)

      declarationProgress = if (info.activeCount > 0) info.declaredCount.toDouble / info.activeCount else 0.0
      _ <- Metrics[F].updateGauge("dag_consensus_stall_declaration_progress", declarationProgress)

      // --- Lagging node detection ---
      // If majority of registered peers are at a STRICTLY HIGHER key,
      // this node is lagging behind the network. Abandon immediately to trigger recovery.
      // IMPORTANT: Only peers at HIGHER keys indicate this node is behind.
      // Peers at the same or lower keys are stale registrations (e.g. from PeerObserved
      // using lastOutcome key, which is always one behind the current round key).
      // Using =!= (any different) instead of > caused cluster-wide cascade failures:
      // all nodes simultaneously detected "lagging" from stale registrations at the
      // previous ordinal, triggering mass recovery downloads with no peers to serve.
      //
      // CR9: Only count peers in Ready state for lagging detection.
      // Peers in Observing/Downloading states report stale observation keys from
      // pre-rollback/pre-download state (trySetObservationKey is set-if-empty, never
      // overwrites). The peerRegistrationStream re-populates registrations from these
      // stale keys immediately after clearAllPeerRegistrations, causing false lagging
      // detection on rollback nodes. Only Ready peers are actively participating in
      // consensus and have accurate keys.
      // TODO: These two Ref reads are non-atomic — peer registrations and responsive peers
      // could observe inconsistent state. The race is benign: worst case is one extra round
      // of lagging detection before self-correcting on the next monitor cycle.
      // Live per-peer tip keys (from incoming keyed rumors). Replaces the old peerRegistrations
      // read which was a one-time join ordinal -- see Bug B: a node that had joined
      // earlier than its current round would never observe "peer ahead" even after the cluster
      // advanced, so isLagging stayed false forever.
      peerCurrentKeys <- storage.getPeerCurrentKeys
      responsivePeers <- clusterStorage.getResponsivePeers
      readyPeerIds = responsivePeers.filter(_.state === NodeState.Ready).map(_.id).toSet
      waitingForReadyPeerCount = responsivePeers.count(_.state === NodeState.WaitingForReady)
      sessionStartedPeerCount = responsivePeers.count(_.state === NodeState.SessionStarted)
      _ <- Metrics[F].updateGauge("dag_consensus_cluster_ready_peer_count", readyPeerIds.size.toLong)
      _ <- Metrics[F].updateGauge("dag_consensus_cluster_waiting_for_ready_peer_count", waitingForReadyPeerCount.toLong)
      _ <- Metrics[F].updateGauge("dag_consensus_cluster_session_started_peer_count", sessionStartedPeerCount.toLong)
      readyPeerRegs = peerCurrentKeys.view.filterKeys(readyPeerIds.contains).toMap
      peersAtHigherKey = readyPeerRegs.count { case (_, peerKey) => peerKey > key }
      totalRegisteredPeers = readyPeerRegs.size
      // Gate on stallCount >= 1 so newly-joined nodes get one full stall cycle (~32s)
      // before being declared lagging. Without this, the observation key refresh causes
      // peers to appear 1 ordinal ahead immediately, triggering rapid-fire abandon loops
      // (5 abandonments in <200ms) that force unnecessary recovery downloads.
      isLagging = totalRegisteredPeers >= 3 && peersAtHigherKey > totalRegisteredPeers / 2 && ms.stallCount >= 1
      totalAllRegs = peerCurrentKeys.size
      _ <- (
        ConsensusLog.warn(
          logger,
          Category.Stall,
          key.toString,
          selfRole(state),
          LogEvent.LaggingNodeDetected,
          "peersAtHigherKey" -> peersAtHigherKey.toString,
          "totalReady" -> totalRegisteredPeers.toString,
          "totalAllRegs" -> totalAllRegs.toString,
          "ownKey" -> key.toString
        ) >>
          Metrics[F].incrementCounter("dag_consensus_lagging_node_detected")
      ).whenA(isLagging)

      // --- Quorum feasibility from handleStall ---
      // handleStall already computed quorum feasibility using the cluster-size-based floor.
      // When eviction would breach quorum, it skips the eviction and propagates quorumInfeasible=true.
      quorumInfeasible = stallResult.quorumInfeasible

      // --- Alpha.98: ready-participation feasibility ---
      // Detects rounds whose Core committee includes peers that are LOCALLY observed as
      // not-Ready. These peers cannot contribute (sign / lead) regardless of how long we
      // wait, so abandoning EARLY (before the stall-cycle / declaration-timeout window
      // expires) lets the next round-start re-attempt with the cluster's then-current
      // observable state.
      //
      // Safety constraint: this is purely a local "yield this round" decision. It does NOT
      // mutate `roundStartFacilitators`, `coreFacilitators`, the facilitator hash, the
      // quorum derivation, or proposal validity. Other peers may make different decisions
      // based on their own local observations; that's fine because the abandon emits no
      // cross-peer state. Determinism of committee derivation is preserved.
      //
      // Condition (codex 2026-05-22 v2 review): peer is in `coreFacilitators` AND not in
      // `readyPeerIdsWithSelf` (current cluster Ready set + selfId; `getResponsivePeers`
      // does not return self, so we MUST add selfId explicitly to avoid classifying self
      // as not-Ready and false-abandoning healthy 2-Core rounds). Once-Ready check is the
      // ONLY exclusion test -- a peer that is WFR-but-caught-up still cannot sign / lead,
      // so excluding them is correct (codex's WFR-promotion-starvation point). The
      // peer-tip-behind subset is computed only as a diagnostic dimension for the log.
      //
      // After excluding non-Ready Core peers, if `activeReady < coreQuorum`, emit
      // `ReadyParticipationQuorumInfeasible`.
      lastOutcomeKey = ctx.lastOutcomeKeyOf(state.lastOutcome)
      readyParticipationStatus = StallDetector.computeReadyParticipationStatus(
        coreFacilitators = state.coreFacilitators.value.toSet,
        readyPeerIds = readyPeerIds,
        selfId = selfId,
        peerCurrentKeysContains = peerCurrentKeys.contains _,
        peerCurrentKeyAtOrAfter = (peerId: PeerId) => peerCurrentKeys.get(peerId).exists(k => Order[Key].gteqv(k, lastOutcomeKey)),
        quorumThresholdFraction = config.quorumThresholdFraction,
        quorumOverride = shrinkDecision.quorumOverride
      )
      readyParticipationInfeasible = readyParticipationStatus.infeasible
      readyParticipationDuringJoiningGrace <- ctx.nodeStorage.isInJoiningGracePeriod
      // Joining grace exists specifically to let a freshly restarted/downloaded source cohort
      // converge on Ready/current observations before aggressive local liveness gates fire. In
      // alpha.103, this gate fired with joiningGrace=true during rollback restart, causing a
      // tight abandon/retry loop at view 0 before sibling validators finished promotion. Keep
      // the diagnostic log/metric, but do not abandon until the grace window has elapsed.
      certifiedTransitionParentHash = ctx.lastSnapshotHashOf(state.lastOutcome)
      certifiedTransitionFromView = state.viewNumber.toLong
      certifiedTransitionToView = certifiedTransitionFromView + 1L
      vccApplyScheduled <- storage.isAssembledVccApplyScheduled(
        key,
        certifiedTransitionParentHash,
        certifiedTransitionFromView,
        certifiedTransitionToView
      )
      timeoutApplyScheduled <- storage.isTimeoutCertificateApplyScheduled(
        key,
        certifiedTransitionParentHash,
        certifiedTransitionFromView,
        certifiedTransitionToView
      )
      certifiedViewApplyScheduled = vccApplyScheduled || timeoutApplyScheduled
      readyParticipationSuppressedForCertifiedView =
        readyParticipationInfeasible && !readyParticipationDuringJoiningGrace && certifiedViewApplyScheduled
      readyParticipationShouldAbandon =
        readyParticipationInfeasible && !readyParticipationDuringJoiningGrace && !certifiedViewApplyScheduled
      _ <- (
        // DEBUG, not WARN: this re-fires every monitor tick while any Core peer is locally observed
        // not-Ready, so at WARN it floods app.log for the whole stall. The operator-relevant event is
        // recorded once by the abandon path (AbandonReason.ReadyParticipationQuorumInfeasible); the
        // metric increment below stays unthrottled for dashboards.
        ConsensusLog.debug(
          logger,
          Category.Stall,
          key.toString,
          selfRole(state),
          LogEvent.StallDetected,
          "trigger" -> "ready_participation_quorum_infeasible",
          "coreSize" -> readyParticipationStatus.coreSize.toString,
          "activeReady" -> readyParticipationStatus.activeReady.toString,
          "coreQuorum" -> readyParticipationStatus.coreQuorum.toString,
          "notReadyCore" -> readyParticipationStatus.notReadyCore.toString,
          "behindNonReady" -> readyParticipationStatus.behindNonReady.toString,
          "joiningGrace" -> readyParticipationDuringJoiningGrace.toString,
          "vccApplyScheduled" -> vccApplyScheduled.toString,
          "timeoutApplyScheduled" -> timeoutApplyScheduled.toString,
          "abandonSuppressedForCertifiedView" -> readyParticipationSuppressedForCertifiedView.toString,
          "lastOutcomeKey" -> lastOutcomeKey.toString,
          "quorumShrinkActive" -> shrinkDecision.active.toString,
          "quorumShrinkSteps" -> shrinkDecision.steps.toString,
          "quorumShrinkRequired" -> shrinkDecision.requiredQuorum.toString,
          "quorumShrinkAnchorSize" -> shrinkDecision.anchor.size.toString
        ) >>
          Metrics[F].incrementCounter("dag_consensus_ready_participation_quorum_infeasible_total") >>
          Metrics[F]
            .incrementCounter("dag_consensus_ready_participation_quorum_infeasible_joining_grace_total")
            .whenA(readyParticipationDuringJoiningGrace) >>
          Metrics[F]
            .incrementCounter("dag_consensus_ready_participation_quorum_infeasible_vcc_suppressed_total")
            .whenA(readyParticipationSuppressedForCertifiedView)
      ).whenA(readyParticipationInfeasible)

      // --- Round timeout / abandon check ---
      // roundElapsed is measured from the current view's start (reset on view change above),
      // and the deadline scales linearly per view with a hard cap — see maxRoundDurationForView.
      roundElapsed = now - newRoundStartTime
      _ <- Metrics[F].updateGauge("dag_consensus_round_elapsed_seconds", roundElapsed.toSeconds.toInt)
      effectiveRoundDeadline = config.maxRoundDuration.map(maxRoundDurationForView(_, state.viewNumber))
      roundTimedOut = effectiveRoundDeadline.exists(roundElapsed >= _)
      // When solo-eviction just fired (evictionEscalated), suppress the stall-cycle check:
      // the reduced committee needs at least one more cycle to attempt the round before we
      // give up. Without this, solo-eviction fires on the same cycle as maxStallCycles
      // abandonment, wasting the eviction.
      stallCycleExceeded = finalStallCount >= config.maxStallCycles && !stallResult.evictionEscalated
      abandonRequested = stallCycleExceeded || roundTimedOut || quorumInfeasible || isLagging || readyParticipationShouldAbandon
      voteLock <- storage.getVoteLock(key)
      sameKeyRestartUnsafe = StallDetector.sameKeyRestartUnsafe(
        viewNumber = state.viewNumber,
        phaseIndex = ops.phaseIndex(state.status),
        voteLockPopulated = voteLock.exists(_.blocksLegacyViewChange),
        mode = storage.viewSafetyMode(state.certifiedConsensusActive)
      )
      // Recreating the same key after this node accepted a proposal/voted can derive a
      // different artifact while first-write-wins declarations and the old signature are
      // still circulating. Only lagging recovery is allowed through this guard because a
      // real peer-ahead download is the boundary that may release the vote lock.
      // A newly-enqueued pacemaker request must get one command-loop turn to emit its
      // votes and run the assembly checks before an already-decided abandon removes the
      // round state. This is a one-monitor-tick grace only: the request latch makes the
      // same request return false on the next tick, so a non-certifying transition still
      // takes the normal abandon path without an unbounded hold.
      shouldAbandon = StallDetector.shouldAbandonThisMonitorTick(
        abandonRequested,
        isLagging,
        sameKeyRestartUnsafe,
        stallResult.pacemakerRequestEnqueued
      )
      restartSuppressed = abandonRequested && !shouldAbandon

      abandonReason: AbandonReason =
        if (isLagging)
          AbandonReason.Lagging(
            peersAtHigherKey,
            totalRegisteredPeers,
            totalAllRegs,
            followerCatchUpEligible = !state.roundStartFacilitators.value.contains(selfId)
          )
        else if (readyParticipationShouldAbandon)
          AbandonReason.ReadyParticipationQuorumInfeasible(
            readyParticipationStatus.activeReady,
            readyParticipationStatus.coreQuorum,
            readyParticipationStatus.notReadyCore
          )
        else if (quorumInfeasible)
          AbandonReason.QuorumInfeasible(stallResult.activeFacilitators, stallResult.quorumSize, stallResult.clusterSize)
        else if (roundTimedOut) AbandonReason.RoundTimeout(roundElapsed.toSeconds, effectiveRoundDeadline.map(_.toSeconds))
        else AbandonReason.MaxStalls(finalStallCount)

      _ <- ConsensusLog
        .info(
          logger,
          Category.Stall,
          key.toString,
          selfRole(state),
          LogEvent.EvictionLoopEscalation,
          "note" -> "solo-eviction suppressed stall-cycle abandonment, giving reduced committee a chance"
        )
        .whenA(stallResult.evictionEscalated && finalStallCount >= config.maxStallCycles)

      _ <- (
        ConsensusLog.warn(
          logger,
          Category.Stall,
          key.toString,
          selfRole(state),
          LogEvent.StallDetected,
          "reason" -> "SAME_KEY_RESTART_UNSAFE",
          "view" -> state.viewNumber.toString,
          "phaseIndex" -> ops.phaseIndex(state.status).toString,
          "highestVotedView" -> voteLock.flatMap(_.highestVotedView).fold("none")(_.toString),
          "requestedReason" -> abandonReason.label,
          "action" -> "retain_attempt_and_wait_for_certified_view_or_peer_ahead_recovery"
        ) >>
          Metrics[F].incrementCounter(
            "dag_consensus_same_key_restart_suppressed_total",
            Seq(Metrics.unsafeLabelName("reason") -> abandonReason.label)
          )
      ).whenA(restartSuppressed && didStall)

      _ <- (
        peerQualityTracker.recordAbandonedMissingPeers(info.missingPeers).whenA(info.missingPeers.nonEmpty) >>
          ConsensusLog
            .info(
              logger,
              Category.Facilitator,
              key.toString,
              selfRole(state),
              LogEvent.RecordingMissingPeers,
              "count" -> info.missingPeers.size.toString
            )
            .whenA(info.missingPeers.nonEmpty) >>
          // Route the abandon through the command queue rather than calling abandonmentTracker.abandonRound
          // directly on this monitor fiber. abandonRound mutates per-key state via condModifyState; running it
          // here raced the command loop's condModifyState calls (the #1 lost-update). The command loop is the
          // single serialized writer (see ConsensusStorage.condModifyState). The monitor still terminates on
          // shouldAbandon below (Right(())), so it cannot enqueue a duplicate for this round.
          queue.offer(
            ConsensusCommand.AbandonRound(key, abandonReason, observedAttemptId, observedResourceGeneration)
          )
      ).whenA(shouldAbandon)

      // --- Update health snapshot ---
      statusName = state.status.getClass.getSimpleName.stripSuffix("$")
      _ <- healthRef.update(
        _.copy(
          key = key.toString.some,
          phase = statusName.some,
          phaseIndex = ops.phaseIndex(state.status).some,
          facilitatorCount = state.facilitators.value.size,
          declaredCount = info.declaredCount,
          activeCount = info.activeCount,
          leader = ConsensusLog.pid(state.leader).some,
          viewNumber = state.viewNumber,
          roundElapsedMs = roundElapsed.toMillis,
          phaseElapsedMs = statusDuration.toMillis,
          stallCount = finalStallCount,
          isRunning = true,
          missingPeers = info.missingPeers.toList.map(ConsensusLog.pid),
          facilitatorIds = state.facilitators.value.map(ConsensusLog.pid)
        )
      )

      // --- Periodic summary logging ---
      timeSinceLastSummary = now - ms.lastSummaryTime
      shouldLogSummary = statusChanged || (timeSinceLastSummary >= config.monitorSummaryInterval && info.declaredCount < info.activeCount)
      newSummaryTime = if (shouldLogSummary) now else ms.lastSummaryTime
      _ <- logSummary(key, state, info, statusDuration, roundElapsed, finalStallCount, statusName)
        .whenA(shouldLogSummary && !shouldAbandon)

      // --- Periodic peer quality score logging ---
      timeSinceLastScoreLog = now - ms.lastScoreLogTime
      shouldLogScores = timeSinceLastScoreLog >= config.peerScoreLogInterval
      newScoreLogTime = if (shouldLogScores) now else ms.lastScoreLogTime
      _ <- logPeerQualityScores(key, selfRole(state)).whenA(shouldLogScores && !shouldAbandon)

      // --- Adaptive sleep ---
      changed = resourcesChanged || statusChanged || didStall
      newNoChangeCount = if (changed) 0 else ms.noChangeCount + 1
      sleepMs = if (changed) basePollInterval else math.min(basePollInterval * (newNoChangeCount + 1), maxPollInterval)
      _ <- Temporal[F].sleep(sleepMs.millis).unlessA(shouldAbandon)

    } yield
      if (shouldAbandon)
        Right(())
      else
        Left(
          MonitorState(
            lastResourcesHash = info.hash,
            lastStatus = Some(state.status),
            statusStartTime = adjustedStatusStartTime,
            roundStartTime = newRoundStartTime,
            noChangeCount = newNoChangeCount,
            // Reset stall count after solo-eviction so the reduced committee gets a
            // fresh start. Without this, the next 200ms poll iteration sees stallCount=3,
            // bumps to 4, and abandons — the suppression only bought one cycle.
            stallCount = if (stallResult.evictionEscalated) 0 else finalStallCount,
            lastSummaryTime = newSummaryTime,
            lastScoreLogTime = newScoreLogTime,
            // Reset on status change so re-entering CollectingFacilities (e.g., after a view change
            // that re-advances into the same phase) starts retransmit budgeting over. Without this,
            // a round that went through a view-change detour could exhaust the cap before the fresh
            // phase gets any retransmit attempts.
            retransmitAttempt = if (statusChanged || stallResult.evictionEscalated) 0 else newRetransmitAttempt,
            lastRetransmitAt = if (statusChanged || stallResult.evictionEscalated) None else newLastRetransmitAt,
            lastView = state.viewNumber,
            lastTimestampPacemakerVoteView =
              if (timestampPacemakerFired) state.viewNumber.some
              else if (viewAdvanced) None
              else ms.lastTimestampPacemakerVoteView,
            admissionTipProbedTargets = admissionVoteEmission.openProbedTargets,
            lastProbationTipProbeAt = admissionVoteEmission.probationObservation match {
              case AdmissionCandidateTipProbe.Observation.Attempted(_) => now.some
              case AdmissionCandidateTipProbe.Observation.NotAttempted => ms.lastProbationTipProbeAt
            }
          )
        )

  private def maybeEmitTimestampPacemakerVote(
    key: Key,
    state: ConsensusState[Key, Status, Outcome, Kind],
    lastTimestampPacemakerVoteView: Option[Int],
    observedPacemakerEpoch: ViewChangeManager.ObservedEpoch
  ): F[Boolean] =
    ctx.lastOutcomeEndTimeMsOf(state.lastOutcome).fold(false.pure[F]) { parentEndTimeMs =>
      for {
        nowWallMs <- Async[F].realTime.map(_.toMillis)
        timeViewHint = ViewFromTime.compute(nowWallMs, parentEndTimeMs.some, config.viewInterval.toMillis)
        selfIsActiveFacilitator = state.facilitators.value.contains(selfId) &&
          !state.withdrawnFacilitators.value.contains(selfId)
        // Timeout-driven VCVs are intentionally limited to facilities/proposal phases. In
        // signatures phase, a timeout is evidence that signature collection or delivery is
        // unhealthy, not necessarily that the leader failed before proposing; the existing
        // grace/heartbeat and abandonment paths handle that narrower failure mode.
        eligiblePhase = ops.phaseIndex(state.status) == 0 || ops.isProposalPhase(state.status)
        alreadyEmitted = lastTimestampPacemakerVoteView.contains(state.viewNumber)
        shouldEmit = selfIsActiveFacilitator &&
          eligiblePhase &&
          timeViewHint > state.viewNumber &&
          !alreadyEmitted
        emitted <-
          if (shouldEmit)
            viewChangeManager.performViewChange(key, observedPacemakerEpoch).flatTap { accepted =>
              (ConsensusLog.info(
                logger,
                Category.Phase,
                key.toString,
                selfRole(state),
                LogEvent.ForcedViewChange,
                "reason" -> "timestamp_pacemaker_timeout",
                "view" -> state.viewNumber.toString,
                "targetView" -> (state.viewNumber + 1).toString,
                "timeViewHint" -> timeViewHint.toString,
                "parentEndTimeMs" -> parentEndTimeMs.toString,
                "nowMs" -> nowWallMs.toString,
                "viewIntervalMs" -> config.viewInterval.toMillis.toString
              ) >> Metrics[F].incrementCounter("dag_consensus_timestamp_pacemaker_vcv_total")).whenA(accepted)
            }
          else false.pure[F]
      } yield emitted
    }

  // ── Timeout Calculation ───────────────────────────────────────────

  /** Per-view effective round deadline: `base + view * 90s`, capped at `2 * base`.
    *
    * Scales up with view so later views (which run in worse conditions) get more slack. Capped so that if the cluster really is stuck,
    * `maxConsecutiveAbandonments` fires via the normal recovery path instead of this safety net running for tens of minutes. Combined with
    * `roundStartTime` resetting on view change, this means each view gets a fresh budget that grows modestly per view.
    */
  private def maxRoundDurationForView(base: FiniteDuration, view: Int): FiniteDuration =
    (base + StallDetector.PerViewRoundDurationIncrement * view.toLong).min(base * 2)

  private def calculateTimeout(
    stallCount: Int,
    info: ResourcesInfo,
    state: ConsensusState[Key, Status, Outcome, Kind]
  ): F[FiniteDuration] =
    for {
      baseDeclarationTimeout <- getCurrentDeclarationTimeout
      // Phase 4: during bootstrap, fresh-start peers need extra headroom before StallDetector
      // fires view change or eviction. Post-bootstrap, we want tighter liveness. The bootstrap
      // classification is consensus-agreed (derived from `recentProofSizes`, a chain-consensus
      // field in the outcome), so all nodes apply the same multiplier deterministically.
      bootstrapActive = ctx.advancer.isBootstrapActive(state.lastOutcome)
      declarationTimeout =
        if (bootstrapActive)
          FiniteDuration((baseDeclarationTimeout.toMillis * config.bootstrapDeclarationTimeoutMultiplier).toLong, MILLISECONDS)
        else
          baseDeclarationTimeout
      // noProgressTimeout only applies in facilities phase (phase index 0) where no declarations at all
      // means peers haven't started. In other phases (proposals, signatures), the standard phase timeout
      // should apply — e.g., in proposals phase the leader may be creating a complex artifact.
      isFacilitiesPhase = ops.phaseIndex(state.status) == 0
      baseTimeout =
        if (stallCount > 0)
          config.reStallTimeout.getOrElse(declarationTimeout)
        else if (info.declaredCount == 0 && isFacilitiesPhase)
          config.noProgressTimeout.getOrElse(declarationTimeout)
        else
          declarationTimeout

      declarationProgress = if (info.activeCount > 0) info.declaredCount.toDouble / info.activeCount else 0.0
      nearCompletion = declarationProgress >= 0.75 && info.declaredCount < info.activeCount

      // Skip near-completion timeout bonus when all missing peers are Unresponsive.
      // Waiting longer for peers that are known-unreachable just delays stall detection.
      allMissingUnresponsive <-
        if (nearCompletion && info.missingPeers.nonEmpty)
          info.missingPeers.toList.forallM { pid =>
            clusterStorage.getPeer(pid).map {
              case Some(peer) => peer.responsiveness === (Unresponsive: PeerResponsiveness)
              case None       => true // Unknown peer treated as unresponsive
            }
          }
        else false.pure[F]

      baseEffective =
        if (nearCompletion && stallCount == 0 && !allMissingUnresponsive)
          baseTimeout + (baseTimeout / 2)
        else baseTimeout

      phaseMultiplier = ops.phaseIndex(state.status) match {
        case 0 => config.facilitiesTimeoutMultiplier
        case 1 => config.proposalsTimeoutMultiplier
        case 2 => config.signaturesTimeoutMultiplier
        case _ => 1.0
      }
    } yield FiniteDuration((baseEffective.toMillis * phaseMultiplier).toLong, MILLISECONDS)

  // ── Stall Handling ────────────────────────────────────────────────

  /** Handle a stall condition. Returns true if a stall was detected.
    *
    * When peers are missing (haven't declared for the current phase), they are evicted from the facilitator set via
    * `performViewChangeWithEviction`. This allows the remaining peers to continue with a reduced quorum instead of being stuck.
    *
    * When all peers have declared but the phase hasn't advanced (e.g., leader hasn't proposed), a normal view change (leader rotation) is
    * performed for proposal phases, or the stall is counted toward abandonment for other phases.
    */
  /** Result of a stall check: whether a stall was detected and whether quorum is infeasible. `evictionEscalated` is true when the
    * ViewChangeManager's solo-eviction escalation fired — suppresses the `finalStallCount >= maxStallCycles` abandonment on this cycle to
    * give the newly-reduced committee a chance to complete the round.
    */
  private case class StallResult(
    didStall: Boolean,
    quorumInfeasible: Boolean,
    activeFacilitators: Int = 0,
    quorumSize: Int = 0,
    clusterSize: Int = 0,
    evictionEscalated: Boolean = false,
    pacemakerRequestEnqueued: Boolean = false
  )

  /** v4.1.0 cluster-majority floor -- terminal halt diagnostic. Observability ONLY: it never changes the abandon/eviction decision (those
    * stay Core-gated, so silent Tier 1 peers cannot trigger an eviction split). When a round has genuinely stalled (`stallCount > 1`)
    * outside bootstrap and the FROZEN ROUND COMMITTEE cannot reach the finality floor (`responding < finalityFloor`), emit ONE WARN naming
    * the CAUSE (which committee members are silent) and the SYMPTOM (responders vs floor): the cluster is too degraded -- silent/flaky
    * peers -- to finalize under the safety floor, and will halt here until peers recover or the committee reconfigures between rounds.
    * `finalityFloor` is `quorumFinalityDecision`'s `baseQuorum`, which is committee-sized only when the floor is actually active, so this
    * never fires for a healthy Core==committee round or during bootstrap.
    */
  private def announceHaltIfDegraded(
    key: Key,
    state: ConsensusState[Key, Status, Outcome, Kind],
    missingPeers: Set[PeerId],
    stallCount: Int,
    finalityFloor: Int
  ): F[Unit] = {
    val committee = state.roundStartFacilitators.value.toSet
    val silentCommittee = committee.intersect(missingPeers)
    val responding = committee.size - silentCommittee.size
    val degraded = !ctx.isInBootstrap(state.lastOutcome) && stallCount > 1 && responding < finalityFloor
    (
      ConsensusLog.warn(
        logger,
        Category.Stall,
        key.toString,
        selfRole(state),
        LogEvent.StallDetected,
        "reason" -> "CONSENSUS_HALTED_DEGRADED",
        "detail" -> "committee cannot reach finality floor; cluster too degraded (silent/flaky peers) -- halting until peer recovery or committee reconfiguration",
        "committee" -> committee.size.toString,
        "finalityFloor" -> finalityFloor.toString,
        "responding" -> responding.toString,
        "silentCommittee" -> ConsensusLog.pids(silentCommittee),
        "view" -> state.viewNumber.toString,
        "stallCount" -> stallCount.toString
      ) >> Metrics[F].incrementCounter("dag_consensus_halted_degraded")
    ).whenA(degraded)
  }

  private def handleStall(
    key: Key,
    state: ConsensusState[Key, Status, Outcome, Kind],
    declarationTimeout: FiniteDuration,
    statusDuration: FiniteDuration,
    declaredCount: Int,
    activeCount: Int,
    missingPeers: Set[PeerId],
    stallCount: Int,
    // v33 quorum-denominator shrink: effective required quorum when the escalated rung is live.
    quorumOverride: Option[Int],
    observedPacemakerEpoch: ViewChangeManager.ObservedEpoch
  ): F[StallResult] =
    if (statusDuration >= declarationTimeout) {
      val statusName = state.status.getClass.getSimpleName.stripSuffix("$")
      val phaseLabel = Seq((Metrics.unsafeLabelName("phase"), statusName))

      if (missingPeers.nonEmpty) {
        // Dual-set accounting (alpha.91): the full-facilitator set is retained for OBSERVABILITY
        // (log lines, peer-quality records) while quorum-infeasibility gates on Core only --
        // mirroring `ConsensusStateAdvancer.maybeGetAllDeclarations` (alpha.89) and
        // `StateTransitions.checkViewChangeAssembly` (alpha.89). Pre-alpha.91 the gate used
        // `state.facilitators.value.size`, which abandoned rounds with `clusterSize=8 required=6`
        // even when Core (3/3) could close a 2-of-3 phase quorum -- task #123 in the user tracker,
        // observed post-alpha.90 at ord 3127058 stuck for 30+ min.
        //
        // Eviction-target candidates are also Core-only to avoid penalising Tier 1 peers for
        // missing a non-Core signing opportunity: the eviction signal exists to restore quorum,
        // and only Core membership affects quorum.
        val totalFacilitators = state.facilitators.value.size
        val remaining = totalFacilitators - missingPeers.size
        val activeCore = state.coreFacilitators.value.toSet -- state.withdrawnFacilitators.value
        val missingCore = activeCore.intersect(missingPeers)
        // Pure helper `computeCoreQuorumStatus` is unit-tested in
        // `StallDetectorCoreQuorumSuite`. Inline aliases below retain the names the
        // surrounding code, logs, and StallResult fields already reference.
        val coreStatus = StallDetector.computeCoreQuorumStatus(
          activeCore = activeCore,
          missingPeers = missingPeers,
          quorumThresholdFraction = config.quorumThresholdFraction,
          quorumOverride = quorumOverride
        )
        val coreSize = coreStatus.coreSize
        val coreRemaining = coreStatus.coreRemaining
        val coreQuorum = coreStatus.coreRequired
        // Backwards-compatible alias for the surrounding code that still reports against
        // `minQuorum` in logs / `StallResult.quorumSize`. The Core-only value is what gates
        // the abandon decision; the field name is retained to keep AbandonReason call sites
        // and downstream metrics labels stable.
        val minQuorum = coreQuorum
        val quorumInfeasible = coreStatus.quorumInfeasible
        // Quorum floor uses the MINIMUM of cluster-wide and round-level quorum:
        //
        // - Cluster quorum (Ready peers): prevents eviction cascades from shrinking quorum
        //   each round until a 2-node fork becomes self-sustaining.
        //
        // - Round quorum (facilitator count): when facilitator subsetting is active
        //   (maxFacilitatorCount < clusterSize), the round may have fewer facilitators
        //   than Ready peers. Using only cluster quorum would make every round with a
        //   missing facilitator quorum-infeasible (e.g., 3 facilitators, 1 missing,
        //   remaining=2 < clusterQuorum=5 -> false QUORUM_INFEASIBLE).
        //
        // The min() ensures both invariants hold:
        // - Without subsetting: roundQuorum == clusterQuorum (all nodes are facilitators)
        // - With subsetting: roundQuorum governs (smaller group, lower threshold)
        clusterStorage.getResponsivePeers.map(_.count(_.state === NodeState.Ready)).flatMap { readyPeerCount =>
          val clusterSize = math.max(readyPeerCount + 1, totalFacilitators)

          // Graduated response: first stall warns and waits, second stall evicts.
          // This gives slow peers (gossip delay, network jitter) an extra timeout window
          // before being removed, preventing premature eviction cascades.
          //
          // The existing chain below is the "graduated response" path
          // (stall warn -> eviction or normal). It runs only when the same key has NOT
          // already been abandoned `config.forceViewChangeAbandonments` times. Once that
          // threshold is crossed, the wrapper below short-circuits to a VCV emission
          // regardless of the "missing-still-responsive" gate inside the quorumInfeasible
          // branch -- otherwise the cluster wedges indefinitely at view=0 because the
          // silent peers' lingering chain-tip gossip keeps the gate satisfied.
          val existingHandle: F[StallResult] =
            if (stallCount == 0) {
              // First timeout -- warn only, give peers one more cycle to respond.
              // Surface both denominators so post-deploy log triage can tell whether the
              // Core gate is the actual blocker or just Tier 1 peers are slow.
              ConsensusLog.warn(
                logger,
                Category.Stall,
                key.toString,
                selfRole(state),
                LogEvent.PeerStallWarning,
                "phase" -> statusName,
                "elapsed" -> s"${statusDuration.toSeconds}s",
                "timeout" -> s"${declarationTimeout.toSeconds}s",
                "progress" -> s"$declaredCount/$activeCount",
                "missing" -> missingPeers.size.toString,
                "missingPeers" -> ConsensusLog.pids(missingPeers),
                "coreActive" -> coreSize.toString,
                "coreRemaining" -> coreRemaining.toString,
                "coreRequired" -> coreQuorum.toString,
                "coreMissing" -> ConsensusLog.pids(missingCore),
                "view" -> state.viewNumber.toString,
                "action" -> "waiting one more cycle before eviction"
              ) >>
                Metrics[F].incrementCounter("dag_consensus_stall_warning") >>
                Metrics[F].incrementCounter("dag_consensus_stall_phase", phaseLabel) >>
                StallResult(didStall = true, quorumInfeasible = false).pure[F] // Count as stall but don't evict
            } else if (quorumInfeasible) {
              // Quorum unreachable (>1/3 missing) — last resort: evict missing peers to restore quorum.
              // This is the ONLY path that performs mid-round eviction.
              //
              // IMPORTANT gate: only evict peers that are ALSO Unresponsive in cluster storage.
              // A peer whose Facility declaration is just slow to arrive at THIS node (gossip jitter,
              // bootstrap stagger) but who is still gossiping and Responsive in clusterStorage is NOT
              // genuinely missing — they'll declare soon. Evicting them splits the cluster:
              // each node's `missingPeers` set reflects local receive timing, not cluster-wide
              // liveness. Different nodes evict different peers → divergent committees
              // (observed Apr 18 E2E: gl0-0/1/3 evicted gl0-2/4 at round 14 while gl0-2/4 evicted
              // the other three, producing a permanent 3-vs-2 split).
              //
              // Cluster storage responsiveness is the authoritative signal: Unresponsive = failed
              // gossip probes over a bounded window, NOT a single late declaration.
              (clusterStorage.getResponsivePeers, getPeerChainTips).tupled.flatMap {
                case (responsivePeersForEviction, chainTips) =>
                  val responsiveIds = responsivePeersForEviction.map(_.id).toSet
                  // Bounded patience: skip eviction while missing peers are still evidence-of-life —
                  // either Responsive in cluster storage OR gossiping chain tips on the mesh — BUT
                  // only within the stall-cycle grace window. After that, BOTH protections drop and
                  // anyone still missing from declarations is evictable regardless of liveness signals.
                  //
                  // The gossip-tip escape hatch matters: `MeshState.getChainTips` returns peers with
                  // retained `(ordinal, hash)` entries that persist until the mesh ages them out. A
                  // zombie node whose consensus fiber wedged at ordinal N but whose gossip fiber
                  // keeps advertising the stale tip would be indefinitely protected if we treated
                  // "has any chain-tip entry" as permanent liveness evidence.
                  // Bounding the chain-tip shield to the same `EvictionSkipMaxStalls`
                  // window as the cluster-storage shield prevents that.
                  //
                  // Observed Apr 18: with an unbounded cluster-responsive gate, a 3-of-5 surviving
                  // group froze at ord 13 forever because supermajority required 4 signers and 2
                  // peers had stopped declaring yet clusterStorage still reported them Responsive.
                  // Same category of bug; fix is the same: bounded patience window.
                  val inGracePeriod = stallCount < StallDetector.EvictionSkipMaxStalls
                  val gossipingTips: Set[PeerId] = chainTips.keySet
                  // Restrict eviction-target candidates to missing CORE peers. Tier 1 peers
                  // outside Core don't gate quorum, so evicting them cannot restore
                  // feasibility and would only penalise them for missing a non-Core signing
                  // opportunity. Per codex's alpha.91 dual-set guidance: keep the
                  // full-facilitator `missingPeers` set for observability, but feed only
                  // `missingCore` into the eviction/VCV-targeting path.
                  val unresponsiveMissing = {
                    val clusterUnresponsive = missingCore.filterNot(responsiveIds.contains)
                    if (inGracePeriod) clusterUnresponsive.filterNot(gossipingTips.contains)
                    else clusterUnresponsive
                  }
                  val bootstrapAllowsSkip = inGracePeriod
                  if (unresponsiveMissing.isEmpty && bootstrapAllowsSkip) {
                    // All missing Core peers are still Responsive -- their declarations haven't
                    // arrived at this node yet but they're alive. Wait another cycle rather
                    // than split.
                    ConsensusLog.info(
                      logger,
                      Category.Stall,
                      key.toString,
                      selfRole(state),
                      LogEvent.StallDetected,
                      "phase" -> statusName,
                      "elapsed" -> s"${statusDuration.toSeconds}s",
                      "progress" -> s"$declaredCount/$activeCount",
                      "missing" -> missingPeers.size.toString,
                      "missingPeers" -> ConsensusLog.pids(missingPeers),
                      "coreMissing" -> ConsensusLog.pids(missingCore),
                      "view" -> state.viewNumber.toString,
                      "stallCount" -> stallCount.toString,
                      "reason" -> "WAITING_MISSING_STILL_RESPONSIVE",
                      "action" -> "skipping eviction -- all missing Core peers still gossiping"
                    ) >>
                      Metrics[F].incrementCounter("dag_consensus_eviction_skipped_still_responsive") >>
                      Metrics[F].incrementCounter("dag_consensus_stall_phase", phaseLabel) >>
                      StallResult(didStall = true, quorumInfeasible = false).pure[F]
                  } else {
                    logger.warn(
                      ConsensusLog.format(
                        Category.Stall,
                        key.toString,
                        selfRole(state),
                        LogEvent.StallDetected,
                        "phase" -> statusName,
                        "elapsed" -> s"${statusDuration.toSeconds}s",
                        "progress" -> s"$declaredCount/$activeCount",
                        "coreActive" -> coreSize.toString,
                        "coreRemaining" -> coreRemaining.toString,
                        "coreRequired" -> coreQuorum.toString,
                        "coreMissing" -> ConsensusLog.pids(missingCore),
                        "facilitatorActive" -> totalFacilitators.toString,
                        "facilitatorRemaining" -> remaining.toString,
                        "facilitatorMissing" -> ConsensusLog.pids(missingPeers),
                        "minQuorum" -> minQuorum.toString,
                        "quorumFeasible" -> "false",
                        "unresponsiveMissing" -> ConsensusLog.pids(unresponsiveMissing),
                        "view" -> state.viewNumber.toString,
                        "stallCount" -> stallCount.toString,
                        "reason" -> "QUORUM_INFEASIBLE_EVICTION"
                      )
                    ) >>
                      Metrics[F].incrementCounter("dag_consensus_peer_eviction") >>
                      Metrics[F].incrementCounter("dag_consensus_stall_phase", phaseLabel) >> {
                        // Phase B1 EvictionVote emission. Entering this branch already implies:
                        //   - stallCount > 0 (stallCount == 0 short-circuited to PEER_STALL_WARNING above)
                        //   - quorumInfeasible (the outer condition)
                        //   - NOT (unresponsiveMissing.isEmpty && bootstrapAllowsSkip) — so
                        //     unresponsiveMissing has at least one peer, or we waited past the
                        //     `EvictionSkipMaxStalls` window for slow-but-responsive peers.
                        // Those conditions are sufficient evidence to emit eviction votes for the
                        // peers that are both missing AND Unresponsive in clusterStorage. Per-target
                        // gates (per-voter cap, not-already-voted, committee membership) are applied
                        // by `selectEvictionTargets`.
                        val committeeSet: Set[PeerId] = state.facilitators.value.toSet
                        val inBootstrap = ctx.isInBootstrap(state.lastOutcome)
                        val evictionEmission = if (inBootstrap || !ctx.membershipPolicy.acceptsEvictionCertificates) {
                          // Phase B1 gate: no emission during bootstrap. Peers flicker Ready/Unresponsive
                          // during initial sync and recovery, and clusterStorage's view of who is
                          // unresponsive is unreliable until at least one full-committee snapshot
                          // exists. Emitting here produced cascading committee splits in the
                          // fork-recovery E2E failures. Matches Phase 4's penalty-suppression pattern.
                          Metrics[F].incrementCounter(
                            "dag_consensus_eviction_vote_suppressed_total",
                            Seq(
                              Metrics.unsafeLabelName("reason") ->
                                (if (inBootstrap) "bootstrap" else "membership_policy")
                            )
                          )
                        } else
                          storage.getResources(key).flatMap { resources =>
                            val alreadyVotedBySelf: Set[PeerId] =
                              resources.evictionVotes.collect {
                                case (target, voters) if voters.contains(selfId) => target
                              }.toSet
                            // Proposal-phase stalls: only the leader is expected to declare a Proposal, so
                            // "nobody declared" means the leader is the unique culprit — everyone else is
                            // correctly waiting. `unresponsiveMissing` would mostly be responsive followers,
                            // and voting to evict them is a false positive. Target the leader specifically.
                            // Observed at ord 3107095: leader cd6362ae (Responsive but pipeline-stalled)
                            // never emitted a Proposal; the cluster abandoned 4x without producing an eviction
                            // vote because the existing target computation matched neither.
                            val candidates: Set[PeerId] =
                              if (ops.isProposalPhase(state.status)) Set(state.leader)
                              else unresponsiveMissing
                            val newTargets: List[PeerId] = StallDetector.selectEvictionTargets(
                              selfId = selfId,
                              unresponsiveMissing = candidates,
                              committee = committeeSet,
                              alreadyVotedBySelf = alreadyVotedBySelf,
                              // Eviction cap from the UNSHRUNK supermajority, not the shrunk quorum: a
                              // liveness shrink must not raise the cap and let the committee be evicted
                              // below the supermajority budget. The abandon gate still uses the shrunk value.
                              minQuorum = coreStatus.baseRequired
                            )
                            newTargets.traverse_ { target =>
                              evictionVoter.emitEvictionVote(key, target, EvictionReason.Silent) >>
                                queue.offer(ConsensusCommand.CheckEvictionAssembly(key, target))
                            }
                          }
                        val phaseIndex = ops.phaseIndex(state.status)
                        val viewChangeOrBinaryHalt =
                          if (
                            phaseIndex == 3 &&
                            storage.viewSafetyMode(state.certifiedConsensusActive) == ViewSafetyMode.LegacyFreezeAfterVote
                          )
                            // Currency BinarySignature has no view/proposal hash on the
                            // legacy wire. Advancing it across a view would let stale
                            // binary declarations satisfy a different attempt, so rc.8
                            // remains deliberately fail-closed here.
                            Metrics[F].incrementCounter(
                              "dag_consensus_binary_finality_view_change_suppressed_total"
                            ) >>
                              StallResult(
                                didStall = true,
                                quorumInfeasible = true,
                                activeFacilitators = coreRemaining,
                                quorumSize = coreQuorum,
                                clusterSize = clusterSize,
                                evictionEscalated = false
                              ).pure[F]
                          else
                            viewChangeManager
                              .performViewChange(key, observedPacemakerEpoch)
                              .map { enqueued =>
                                // Propagate the Core-only numbers so the resulting
                                // `AbandonReason.QuorumInfeasible` satisfies its `active < required`
                                // invariant and `AbandonmentTracker`'s isolated/quorum-impossible
                                // classifier reads the correct active count.
                                StallResult(
                                  didStall = true,
                                  quorumInfeasible = true,
                                  activeFacilitators = coreRemaining,
                                  quorumSize = coreQuorum,
                                  clusterSize = clusterSize,
                                  evictionEscalated = false,
                                  pacemakerRequestEnqueued = enqueued
                                )
                              }

                        evictionEmission >>
                          // Phase 2+: no mid-round eviction. An unlocked node requests
                          // leader rotation (gossip a VCV, wait for a quorum-certified VCC).
                          // A Global L0 node that has already voted stays on the old attempt:
                          // recreating the key or helping certify a higher view is unsafe on the
                          // legacy artifact-only signature wire. Peer-ahead recovery or an
                          // operator restart is the explicit availability boundary until v35.
                          //
                          // Propagate quorumInfeasible=true so the outer monitor's abandonment
                          // classification can distinguish a genuine quorum-infeasible stall (this
                          // branch) from ordinary stall-cycle expiry (the `else` below). Without
                          // this, AbandonReason.QuorumInfeasible never fires and the new
                          // escalate-vs-suppress logic in AbandonmentTracker is unreachable.
                          viewChangeOrBinaryHalt
                      }
                  }
              }
            } else {
              // Normal stall with quorum still feasible — just count the cycle.
              // The round will complete at quorum threshold (ceil(N*2/3)) without
              // needing to evict anyone. Non-responding peers become non-signers
              // in the outcome and get penalized between rounds.
              Metrics[F].incrementCounter("dag_consensus_stall_phase", phaseLabel) >>
                (if (ops.phaseIndex(state.status) == 2)
                   // Route through the shared pacemaker gate. Once this node has signed,
                   // ViewChangeManager suppresses the VCV/TC: the legacy artifact hash does
                   // not bind the full proposal envelope, so a higher-view re-vote is unsafe.
                   // An unlocked node may still help the unlocked quorum advance.
                   Metrics[F].incrementCounter("dag_consensus_signature_phase_view_change_requested_total") >>
                     viewChangeManager
                       .performViewChange(key, observedPacemakerEpoch)
                       .map(enqueued =>
                         StallResult(
                           didStall = true,
                           quorumInfeasible = false,
                           pacemakerRequestEnqueued = enqueued
                         )
                       )
                 else StallResult(didStall = true, quorumInfeasible = false).pure[F])
            }

          // Defensive force-VCV request. Reads the cluster-tracked
          // consecutiveAbandonments counter (same one logged as `consecutiveAbandonments=` in
          // ROUND_ABANDONED_TRACKED / RETRIABLE_ESCALATED). When it crosses the threshold for
          // THIS ordinal, enqueue a serialized view-change request so all unlocked peers
          // converge on (fromView=v, toView=v+1) via the existing VCC machinery. Global L0
          // peers already locked by a legacy artifact vote deliberately suppress emission.
          // We do not
          // mutate the facilitator set (April 2026's failed approach was mid-round eviction);
          // the round retries with view=v+1, a deterministically-different leader, and the
          // same supermajority threshold. Honest nodes that cross the abandonment threshold
          // within bounded skew of each other emit byte-identical VCVs at the same
          // (fromView, toView, facilitatorsHash) tuple; VCC assembly succeeds once
          // ceil(N*2/3) of the round-start committee has voted. Stragglers catch up via the
          // existing "advance localView on observing higher-view message" path.
          abandonmentTracker.consecutiveAbandonmentsFor(key).flatMap { consecutiveAbandonments =>
            val binaryViewChangeAllowed =
              ops.phaseIndex(state.status) != 3 ||
                storage.viewSafetyMode(state.certifiedConsensusActive) != ViewSafetyMode.LegacyFreezeAfterVote
            if (consecutiveAbandonments >= config.forceViewChangeAbandonments && binaryViewChangeAllowed) {
              ConsensusLog
                .warn(
                  logger,
                  Category.Stall,
                  key.toString,
                  selfRole(state),
                  LogEvent.ForcedViewChange,
                  "consecutiveAbandonments" -> consecutiveAbandonments.toString,
                  "threshold" -> config.forceViewChangeAbandonments.toString,
                  "phase" -> statusName,
                  "view" -> state.viewNumber.toString,
                  "progress" -> s"$declaredCount/$activeCount",
                  "leader" -> ConsensusLog.pid(state.leader),
                  "missingPeers" -> ConsensusLog.pids(missingPeers)
                ) >>
                Metrics[F].incrementCounter("dag_consensus_forced_view_change_total") >>
                Metrics[F].incrementCounter("dag_consensus_stall_phase", phaseLabel) >>
                viewChangeManager
                  .performViewChange(key, observedPacemakerEpoch)
                  .map { enqueued =>
                    // Same Core-only propagation as the quorumInfeasible eviction branch:
                    // when this path emits a forced view change AND `quorumInfeasible` is true,
                    // the outer monitor builds `AbandonReason.QuorumInfeasible` from these
                    // fields; the invariant `active < required` must hold against Core numbers.
                    StallResult(
                      didStall = true,
                      quorumInfeasible = quorumInfeasible,
                      activeFacilitators = coreRemaining,
                      quorumSize = coreQuorum,
                      clusterSize = clusterSize,
                      pacemakerRequestEnqueued = enqueued
                    )
                  }
            } else existingHandle
          }
        }
      } else if (ops.isProposalPhase(state.status)) {
        // All declared but leader hasn't proposed → normal view change (leader rotation only)
        ConsensusLog.warn(
          logger,
          Category.Stall,
          key.toString,
          selfRole(state),
          LogEvent.LeaderStall,
          "phase" -> statusName,
          "elapsed" -> s"${statusDuration.toSeconds}s",
          "timeout" -> s"${declarationTimeout.toSeconds}s",
          "progress" -> s"$declaredCount/$activeCount",
          "leader" -> ConsensusLog.pid(state.leader),
          "view" -> state.viewNumber.toString
        ) >>
          Metrics[F].incrementCounter("dag_consensus_view_change") >>
          Metrics[F].incrementCounter("dag_consensus_stall_phase", phaseLabel) >>
          viewChangeManager
            .performViewChange(key, observedPacemakerEpoch)
            .map(enqueued =>
              StallResult(
                didStall = true,
                quorumInfeasible = false,
                pacemakerRequestEnqueued = enqueued
              )
            )
      } else if (ops.phaseIndex(state.status) == 2) {
        // No peer is missing but the MajoritySignature phase still did not advance.
        // Emit the same certified view-change vote as the missing-peer path; applying
        // it gets one synchronous finalization attempt before any view mutation.
        ConsensusLog.warn(
          logger,
          Category.Stall,
          key.toString,
          selfRole(state),
          LogEvent.StallDetected,
          "phase" -> statusName,
          "elapsed" -> s"${statusDuration.toSeconds}s",
          "timeout" -> s"${declarationTimeout.toSeconds}s",
          "progress" -> s"$declaredCount/$activeCount",
          "reason" -> "SIGNATURE_PHASE_STALLED",
          "action" -> "request_view_change_vote"
        ) >>
          Metrics[F].incrementCounter("dag_consensus_signature_phase_view_change_requested_total") >>
          Metrics[F].incrementCounter("dag_consensus_stall_phase", phaseLabel) >>
          viewChangeManager
            .performViewChange(key, observedPacemakerEpoch)
            .map(enqueued =>
              StallResult(
                didStall = true,
                quorumInfeasible = false,
                pacemakerRequestEnqueued = enqueued
              )
            )
      } else {
        // All declared but phase hasn't advanced → count toward abandon
        ConsensusLog.warn(
          logger,
          Category.Stall,
          key.toString,
          selfRole(state),
          LogEvent.StallDetected,
          "phase" -> statusName,
          "elapsed" -> s"${statusDuration.toSeconds}s",
          "timeout" -> s"${declarationTimeout.toSeconds}s",
          "progress" -> s"$declaredCount/$activeCount"
        ) >>
          Metrics[F].incrementCounter("dag_consensus_stall_detected") >>
          Metrics[F].incrementCounter("dag_consensus_stall_phase", phaseLabel) >>
          StallResult(didStall = true, quorumInfeasible = false).pure[F]
      }
    } else {
      StallResult(didStall = false, quorumInfeasible = false).pure[F]
    }

  // ── Resource Info ─────────────────────────────────────────────────

  private def getResourcesInfo(
    state: ConsensusState[Key, Status, Outcome, Kind],
    resources: ConsensusResources[Artifact, Kind]
  ): ResourcesInfo = {
    val active = state.facilitators.value.toSet -- state.withdrawnFacilitators.value
    ops.maybeCollectingKind(state.status) match {
      case Some(kind) =>
        val getter = ops.kindGetter(kind)
        val respondedPeers = resources.peerDeclarationsMap.collect {
          case (pid, decls) if active.contains(pid) && getter(decls).isDefined => pid
        }.toSet
        val missing = active -- respondedPeers
        ResourcesInfo(
          hash = respondedPeers.hashCode(),
          declaredCount = respondedPeers.size,
          activeCount = active.size,
          missingPeerIds = missing.toList.map(_.value.value.take(8)).toSet,
          missingPeers = missing
        )
      case None =>
        ResourcesInfo(
          hash = resources.peerDeclarationsMap.keySet.hashCode(),
          declaredCount = resources.peerDeclarationsMap.size,
          activeCount = active.size,
          missingPeerIds = Set.empty,
          missingPeers = Set.empty
        )
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────

  private def isLeaderUnresponsive(leader: PeerId): F[Boolean] =
    if (leader == ctx.selfId)
      false.pure[F] // Local node is always responsive to itself
    else
      clusterStorage.getPeer(leader).map {
        case Some(peer) => peer.responsiveness === (Unresponsive: PeerResponsiveness)
        case None       => true
      }

  private def getCurrentDeclarationTimeout: F[FiniteDuration] =
    ctx.nodeStorage.isInJoiningGracePeriod.map { isInJoiningGracePeriod =>
      if (isInJoiningGracePeriod) config.timeTriggerInterval else config.declarationTimeout
    }

  // ── Logging ───────────────────────────────────────────────────────

  private def logSummary(
    key: Key,
    state: ConsensusState[Key, Status, Outcome, Kind],
    info: ResourcesInfo,
    statusDuration: FiniteDuration,
    roundElapsed: FiniteDuration,
    stallCount: Int,
    statusName: String
  ): F[Unit] = {
    val withdrawnCount = state.withdrawnFacilitators.value.size
    val missingCount = info.missingPeers.size

    val summaryPairs = Seq(
      "phase" -> statusName,
      "progress" -> s"${info.declaredCount}/${info.activeCount}",
      "facilitators" -> state.facilitators.value.size.toString,
      "phaseElapsed" -> s"${statusDuration.toSeconds}s",
      "roundElapsed" -> s"${roundElapsed.toSeconds}s",
      "stallCount" -> stallCount.toString,
      "leader" -> ConsensusLog.pid(state.leader)
    ) ++
      (if (state.viewNumber > 0) Seq("view" -> state.viewNumber.toString) else Seq.empty) ++
      (if (withdrawnCount > 0) Seq("withdrawn" -> withdrawnCount.toString) else Seq.empty) ++
      (if (missingCount > 0) Seq("missing" -> missingCount.toString) else Seq.empty)

    ConsensusLog.info(logger, Category.Stall, key.toString, selfRole(state), LogEvent.RoundMonitor, summaryPairs: _*)
  }

  private def logPeerQualityScores(key: Key, role: String): F[Unit] =
    peerQualityTracker.getQualityScores.flatMap { scores =>
      if (scores.nonEmpty) {
        val sorted = scores.toList.sortBy(-_._2)
        val total = sorted.size
        val healthy = sorted.count(_._2 >= 0.7)
        val degraded = sorted.count(s => s._2 >= 0.3 && s._2 < 0.7)
        val unhealthy = sorted.count(_._2 < 0.3)
        ConsensusLog.info(
          logger,
          Category.Facilitator,
          key.toString,
          role,
          LogEvent.PeerQuality,
          "summary" -> s"healthy=$healthy,degraded=$degraded,unhealthy=$unhealthy",
          "trackedPeers" -> total.toString
        )
      } else
        ConsensusLog
          .debug(logger, Category.Facilitator, key.toString, role, LogEvent.PeerQuality, "trackedPeers" -> "0")
    }

  /** Admission emission trigger. Core peers emit a bounded open-expansion vote for the parent Proposal's canonical nominee only after a
    * fresh direct metadata response names the exact parent and the nominee has sent a Facility bound to this current round. Probation
    * recovery remains a separate lane because a peer deliberately held in probation cannot emit a Facility.
    *
    * '''Witness channel''': probation peers are excluded from `state.facilitators` by the state-creator filter, so they never send Facility
    * declarations for the active round. The committee cannot witness them via `resources.peerDeclarationsMap`. Instead, every peer —
    * including probation peers — gossips its local chain tip via `EventGossipDaemon` IHave messages on the heartbeat+pull loops. Those tips
    * land in `MeshState.getChainTips`, exposed here through the `getPeerChainTips` thunk. A probation peer whose mesh-reported
    * `snapshotHash` matches `lastOutcome.finished.snapshotHash` or whose ordinal is within tolerance of the committed ordinal is treated as
    * a witnessed candidate for re-admission. Exact hash matching was too strict on a live chain: Ready followers commonly advertise N while
    * the committee has just finalized N+1.
    *
    * Safety: storage is first-write-wins per `(voter, target)`, so re-invocations within a round are idempotent. The fixed open target is
    * probed once per monitor attempt regardless of cached gossip tips. A failed, stale, or conflicting direct response overrides cached
    * evidence and causes abstention. The fixed probation target is probed at most once per local one-second maximum-poll interval, and a
    * throttled tick preserves its streak without authorizing a vote from stale evidence.
    *
    * Determinism: every Core peer reads the same proposal-carried parent nominee. A local chain-tip observation controls abstention only.
    * Committee mutation still requires a quorum-certified AdmissionCertificate in an accepted proposal.
    */
  private def maybeEmitAdmissionVotes(
    key: Key,
    state: ConsensusState[Key, Status, Outcome, Kind],
    resources: ConsensusResources[Artifact, Kind],
    roundStartFacilitatorsHash: Hash,
    probedTargets: Set[PeerId],
    probationProbeDue: Boolean
  ): F[AdmissionVoteEmission] = {
    val locallyObservedParentSigners = locallyObservedParentSignersOf(state.lastOutcome)
    val parentOrdinal = ctx.lastOutcomeKeyOf(state.lastOutcome) match {
      case ordinal: SnapshotOrdinal => ordinal.value.value.some
      case _                        => none[Long]
    }
    val parentHash = lastSnapshotHashOf(state.lastOutcome)

    val observeHistory = StallDetector.observeAdmissionProofHistory(
      admissionProofHistoryRef,
      locallyObservedParentSigners,
      parentOrdinal,
      parentHash
    )

    observeHistory.flatMap { history =>
      maybeEmitAdmissionVotesWithHistory(
        key,
        state,
        resources,
        roundStartFacilitatorsHash,
        probedTargets,
        probationProbeDue,
        history
      )
    }
  }

  private def maybeEmitAdmissionVotesWithHistory(
    key: Key,
    state: ConsensusState[Key, Status, Outcome, Kind],
    resources: ConsensusResources[Artifact, Kind],
    roundStartFacilitatorsHash: Hash,
    probedTargets: Set[PeerId],
    probationProbeDue: Boolean,
    locallyObservedParentProofHistory: Option[AdmissionProofHistory.History]
  ): F[AdmissionVoteEmission] = {
    val probation = probationPeersOf(state.lastOutcome)
    val alreadyVotedBySelf: Set[PeerId] = resources.admissionVotes.collect {
      case (target, voters) if voters.contains(selfId) => target
    }.toSet
    val expectedTip: Hash = lastSnapshotHashOf(state.lastOutcome)
    val expectedOrdinal = ctx.lastOutcomeKeyOf(state.lastOutcome) match {
      case ordinal: SnapshotOrdinal => ordinal.some
      case _                        => none[SnapshotOrdinal]
    }
    val committee = state.roundStartFacilitators.value.toSet
    val core = state.coreFacilitators.value.toSet
    val requireCoreCertification =
      ctx.membershipPolicy.allowsCertifiedAtomicReplacement(state.certifiedConsensusActive)
    val selfIsCore = core.contains(selfId)
    val admissionVoteAuthority =
      AdmissionVoterPool.allowsVoteEmission(selfId, requireCoreCertification, core)
    // Preserve the cached gossip-tip policy for layers without a direct probe (currently
    // Currency L0). Global L0 open and probation lanes use fresh exact observations below.
    // Certified atomic replacement deliberately tightens the legacy cache predicate without
    // allowing a failed direct response to fall back to cached evidence.
    def isAdmissionReadyTip(tip: ChainTip): Boolean =
      AdmissionTipReadiness.isCachedReady(tip, expectedTip, expectedOrdinal, requireCoreCertification)
    val coreSize = math.max(1, state.coreFacilitators.value.size)
    val voteQuorum = math.max(1, QuorumPolicy.fromFraction(coreSize, config.quorumThresholdFraction))
    val bootstrapActive = ctx.isInBootstrap(state.lastOutcome)
    val locallyObservedParentSigners = locallyObservedParentSignersOf(state.lastOutcome)
    val configuredMaxAdmissions = math.max(0, config.activeAdmissionMaxExpansionPerRound)
    val admissionBatchSize = math.max(1, configuredMaxAdmissions)
    val headroomGateActive = OpenAdmissionPolicy.headroomRequired(
      certifiedConsensusActive = state.certifiedConsensusActive,
      bootstrapActive = bootstrapActive,
      currentCommitteeSize = committee.size,
      maxAdmissionSeats = admissionBatchSize,
      bootstrapCompleteProofsThreshold = config.bootstrapCompleteProofsThreshold
    )
    val openAdmissionPolicy = OpenAdmissionPolicy.evaluate(
      cadenceAllowed = openAdmissionCadenceOf(key),
      currentCommittee = committee,
      locallyObservedParentSigners = locallyObservedParentSigners,
      quorumThresholdFraction = config.quorumThresholdFraction,
      // Bootstrap bypass remains only below the batch that reaches the full-committee proof
      // threshold. The crossing batch must already support the floor it can activate.
      headroomGateActive = headroomGateActive,
      // The proposal validator may accept this many certificates. Prove headroom for the
      // largest possible batch rather than assuming the IntegrationNet value remains one.
      maxAdmissionSeats = admissionBatchSize,
      locallyObservedParentProofHistory = locallyObservedParentProofHistory
    )
    // The atomic lane cannot wait for this node to assemble an ECS: certificates are proposal
    // payloads, not separately gossiped resources, so asymmetric vote delivery could otherwise
    // leave every Core voter holding its own audit vote while no voter holds a complete ECS.
    // Instead, the exact hysteresis-qualified self vote emitted by the round-start auditor is
    // causal authority to emit the matching open ACS vote. Proposal construction/validation still
    // requires both quorum certificates, so this local gate cannot change membership by itself.
    val selfEvictionVotes = resources.evictionVotes.iterator.flatMap {
      case (target, voters) => voters.get(selfId).map(vote => target -> vote)
    }.toMap
    val atomicReplacementIntentTargets = StallDetector.atomicReplacementIntentTargets(
      selfId = selfId,
      selfIsCore = selfIsCore,
      atomicReplacementEnabled = requireCoreCertification,
      cadenceAllowed = openAdmissionPolicy.cadenceAllowed,
      currentCommittee = committee,
      parentRoundCommittee = parentRoundCommitteeOf(state.lastOutcome),
      selfEvictionVotes = selfEvictionVotes,
      expectedFacilitatorsHash = roundStartFacilitatorsHash,
      expectedParentHash = expectedTip,
      entropy = expectedTip,
      maxTargets = configuredMaxAdmissions
    )
    val hasAtomicReplacementIntent = atomicReplacementIntentTargets.nonEmpty
    val atomicReplacementAdmissionAllowed =
      hasAtomicReplacementIntent
    val maxOpenAdmissions =
      if (openAdmissionPolicy.allowsOpenAdmission) configuredMaxAdmissions
      else atomicReplacementIntentTargets.size
    val canonicalNominees = admissionNomineesOf(state.lastOutcome)
    // Open expansion has a fixed per-voter target set for the entire round. Selection happens
    // before the local at-tip check, so a missing observation is an abstention rather than a walk
    // to the next candidate. Probation votes remain a separate, wider liveness lane below.
    val openAdmissionTargets = StallDetector.openAdmissionTargets(
      // A replacement target and admission candidate must be distinct. This remains true even
      // if a malformed/stale parent happened to carry the evicted peer as its nominee.
      candidates = StallDetector.excludeAtomicReplacementTargets(canonicalNominees, atomicReplacementIntentTargets),
      committee = committee,
      probation = probation,
      alreadyVotedBySelf = alreadyVotedBySelf,
      entropy = expectedTip,
      maxOpenAdmissions = maxOpenAdmissions,
      selfIsCore = selfIsCore
    )
    val probationAdmissionVoteAllowed =
      openAdmissionPolicy.allowsProbationAdmission && admissionVoteAuthority

    val policyOutcome =
      openAdmissionPolicy.headroom match {
        case Some(headroom) if !headroom.allowsExpansion && atomicReplacementAdmissionAllowed => "atomic_replacement"
        case Some(headroom) if !headroom.allowsExpansion                                      => "insufficient_headroom"
        case _ if openAdmissionPolicy.sustainedHeadroom.exists(!_.allowsAdmission) =>
          "insufficient_sustained_headroom"
        case _ if !openAdmissionPolicy.cadenceAllowed                         => "off_cadence"
        case Some(_)                                                          => "allowed"
        case None if bootstrapActive && locallyObservedParentSigners.nonEmpty => "bootstrap_cadence_only"
        case None                                                             => "cadence_only"
      }
    val policyOutcomeLabel = Metrics.unsafeLabelName("outcome")
    val recordOpenAdmissionPolicy =
      Metrics[F].updateGauge(
        "dag_consensus_open_admission_cadence_allowed",
        if (openAdmissionPolicy.cadenceAllowed) 1L else 0L
      ) >>
        Metrics[F].updateGauge(
          "dag_consensus_open_admission_vote_allowed",
          if (openAdmissionPolicy.allowsOpenAdmission || atomicReplacementAdmissionAllowed) 1L else 0L
        ) >>
        Metrics[F].updateGauge(
          "dag_consensus_probation_admission_vote_allowed",
          if (probationAdmissionVoteAllowed) 1L else 0L
        ) >>
        Metrics[F].updateGauge(
          "dag_consensus_open_admission_headroom_gate_active",
          if (openAdmissionPolicy.headroom.nonEmpty) 1L else 0L
        ) >>
        Metrics[F].updateGauge(
          "dag_consensus_open_admission_sustained_gate_active",
          if (openAdmissionPolicy.sustainedHeadroom.exists(_.raisesFinalityFloor)) 1L else 0L
        ) >>
        Metrics[F].updateGauge(
          "dag_consensus_open_admission_sustained_history_depth",
          openAdmissionPolicy.sustainedHeadroom.fold(0L)(_.observedParents.toLong)
        ) >>
        Metrics[F].updateGauge(
          "dag_consensus_open_admission_sustained_history_required",
          openAdmissionPolicy.sustainedHeadroom.fold(0L)(_.requiredParents.toLong)
        ) >>
        Metrics[F].updateGauge(
          "dag_consensus_open_admission_sustained_qualifying_parents",
          openAdmissionPolicy.sustainedHeadroom.fold(0L)(_.qualifyingParents.toLong)
        ) >>
        Metrics[F].updateGauge(
          "dag_consensus_open_admission_sustained_vote_allowed",
          if (openAdmissionPolicy.sustainedHeadroom.forall(_.allowsAdmission)) 1L else 0L
        ) >>
        openAdmissionPolicy.headroom.fold(Async[F].unit) { headroom =>
          Metrics[F].updateGauge(
            "dag_consensus_open_admission_observed_current_committee_signers",
            headroom.observedCurrentCommitteeSigners.toLong
          ) >>
            Metrics[F].updateGauge(
              "dag_consensus_open_admission_next_committee_size",
              headroom.nextCommitteeSize.toLong
            ) >>
            Metrics[F].updateGauge(
              "dag_consensus_open_admission_next_finality_floor",
              headroom.nextFinalityFloor.toLong
            ) >>
            Metrics[F].updateGauge(
              "dag_consensus_open_admission_finality_margin",
              headroom.margin.toLong
            )
        } >>
        Metrics[F].incrementCounter(
          "dag_consensus_open_admission_policy_total",
          Seq(policyOutcomeLabel -> policyOutcome)
        )

    val clearStreaks =
      if (probation.isEmpty) b2AtTipStreakRef.set(Map.empty)
      else Async[F].unit

    if (!admissionVoteAuthority || (probation.isEmpty && openAdmissionTargets.isEmpty))
      (recordOpenAdmissionPolicy >> clearStreaks >> Metrics[F].updateGauge(
        "dag_consensus_open_admission_candidate_current_facility",
        0L
      )).as(
        AdmissionVoteEmission(probedTargets, AdmissionCandidateTipProbe.Observation.NotAttempted)
      )
    else
      recordOpenAdmissionPolicy >> clearStreaks >> getPeerChainTips.flatMap { cachedChainTips =>
        // Global L0 probes one fixed target per lane. The probation target may be retried once per
        // local one-second maximum-poll interval until its stability streak is satisfied; the open target remains one-shot
        // per monitor attempt. Run the two bounded lane probes independently so an unavailable
        // probation peer cannot starve a healthy open nominee. Neither lane walks to a second
        // target after failure. Currency supplies no probe and keeps its cached-mesh behavior
        // byte-for-behavior.
        val fixedProbationTarget =
          admissionCandidateTipProbe.flatMap(_ => AdmissionCandidateTipProbe.probationTargetForRound(probation, expectedTip))
        val probationProbeTarget = for {
          _ <- admissionCandidateTipProbe
          target <- fixedProbationTarget
          if probationAdmissionVoteAllowed
          if !alreadyVotedBySelf.contains(target)
          if probationProbeDue
        } yield target
        val fixedOpenTarget = for {
          _ <- admissionCandidateTipProbe
          target <- AdmissionCandidateTipProbe.targetForRound(openAdmissionTargets, probedTargets)
        } yield target
        // Wait for actual current-key consensus participation before spending the one-shot
        // authenticated metadata probe. If the Facility arrives later in this round, a later
        // monitor tick can still launch the probe; an absent Facility does not burn the cadence.
        val openProbeTarget = fixedOpenTarget.filter { target =>
          StallDetector.hasCurrentRoundFacility(
            resources.peerDeclarationsMap,
            selfId,
            target,
            expectedTip,
            expectedOrdinal
          )
        }

        admissionCandidateTipProbe
          .fold(List.empty[(PeerId, AdmissionCandidateTipProbe.Lane, Option[ChainTip])].pure[F]) { probes =>
            AdmissionCandidateTipProbe.runLaneProbes(probes, probationProbeTarget, openProbeTarget)
          }
          .flatMap { directProbeResults =>
            val probationObservation: AdmissionCandidateTipProbe.Observation =
              directProbeResults.collectFirst {
                case (_, lane, tip) if lane.isProbationRecovery =>
                  AdmissionCandidateTipProbe.Observation.Attempted(tip)
              }.getOrElse(AdmissionCandidateTipProbe.Observation.NotAttempted)
            val openObservation: AdmissionCandidateTipProbe.Observation =
              directProbeResults.collectFirst {
                case (_, lane, tip) if !lane.isProbationRecovery =>
                  AdmissionCandidateTipProbe.Observation.Attempted(tip)
              }.getOrElse(AdmissionCandidateTipProbe.Observation.NotAttempted)
            val nextProbedTargets = probedTargets ++ directProbeResults.collect {
              case (target, lane, _) if !lane.isProbationRecovery => target
            }
            // A fresh direct response is admitted only when it names the exact parent.
            // The bounded-lag allowance remains for the asynchronously sampled gossip cache,
            // whose value can legitimately trail the current round.
            val chainTips = directProbeResults.foldLeft(cachedChainTips) {
              case (tips, (target, _, observedTip)) =>
                AdmissionCandidateTipProbe.mergeExactResult(
                  tips,
                  (target -> observedTip).some,
                  expectedTip,
                  expectedOrdinal
                )
            }
            val directProbeOutcomes = directProbeResults.map {
              case (target, lane, Some(tip)) if AdmissionTipReadiness.isExact(tip, expectedTip, expectedOrdinal) =>
                (target, lane, "ready")
              case (target, lane, Some(_)) => (target, lane, "not_ready")
              case (target, lane, None)    => (target, lane, "unavailable")
            }
            def hasCurrentRoundFacility(target: PeerId): Boolean =
              StallDetector.hasCurrentRoundFacility(
                resources.peerDeclarationsMap,
                selfId,
                target,
                expectedTip,
                expectedOrdinal
              )
            val openCandidateCurrentFacility = fixedOpenTarget.exists(hasCurrentRoundFacility)
            val openAlignmentOutcomes = directProbeResults.collect {
              case (target, lane, None) if !lane.isProbationRecovery =>
                (target, "tip_unavailable")
              case (target, lane, Some(tip))
                  if !lane.isProbationRecovery && !AdmissionTipReadiness.isExact(tip, expectedTip, expectedOrdinal) =>
                (target, "tip_not_exact")
              case (target, lane, Some(_)) if !lane.isProbationRecovery && hasCurrentRoundFacility(target) =>
                (target, "aligned")
              case (target, lane, Some(_)) if !lane.isProbationRecovery =>
                (target, "facility_missing_or_misaligned")
            }
            val recordDirectProbe = directProbeOutcomes.traverse_ {
              case (_, lane, outcome) =>
                Metrics[F].incrementCounter(
                  "dag_consensus_open_admission_tip_probe_total",
                  Seq(Metrics.unsafeLabelName("outcome") -> outcome, Metrics.unsafeLabelName("lane") -> lane.label)
                )
            }
            val recordOpenAlignment = openAlignmentOutcomes.traverse_ {
              case (_, outcome) =>
                Metrics[F].incrementCounter(
                  "dag_consensus_open_admission_candidate_alignment_total",
                  Seq(Metrics.unsafeLabelName("outcome") -> outcome)
                )
            }
            val recordOpenCandidateFacility =
              Metrics[F].updateGauge(
                "dag_consensus_open_admission_candidate_current_facility",
                if (openCandidateCurrentFacility) 1L else 0L
              )

            // Global L0 advances the fixed probation streak only from this tick's fresh exact
            // direct response. Currency has no direct probe and retains the legacy cached-mesh
            // streak behavior for every probation peer.
            val updateStreaks =
              if (probation.isEmpty) Map.empty[PeerId, Int].pure[F]
              else if (admissionCandidateTipProbe.nonEmpty)
                b2AtTipStreakRef.modify { previous =>
                  val updated = AdmissionCandidateTipProbe.updateExactProbationStreak(
                    previous,
                    fixedProbationTarget,
                    probationObservation,
                    expectedTip,
                    expectedOrdinal
                  )
                  (updated, updated)
                }
              else
                b2AtTipStreakRef.modify { prev =>
                  val updated: Map[PeerId, Int] = probation.iterator.map { pid =>
                    val atTip = chainTips.get(pid).exists(isAdmissionReadyTip)
                    val next = if (atTip) prev.getOrElse(pid, 0) + 1 else 0
                    pid -> next
                  }.toMap
                  (updated, updated)
                }

            recordDirectProbe >> recordOpenAlignment >> recordOpenCandidateFacility >> updateStreaks.flatMap { streaks =>
              // Require multiple consecutive at-tip observations before emitting. A single tick of
              // match is insufficient evidence that the peer has stably caught up — observed
              // in E2E: B1 evicted gl0-2 while it was still downloading, then B2 re-admitted
              // it the instant its recovery download produced the committed tip hash; committee
              // snapped back to 5 just before isolation, leaving only 3 active signers against a
              // declaration quorum of 4 (ceil(5*0.67)). Requiring a stability streak delays
              // re-admission until the peer has held the tip for at least
              // `b2AdmissionAtTipStreak` fresh, rate-limited direct observations. With the bridge's
              // one-second minimum probe interval, the compiled default streak of two spans multiple seconds
              // of sustained correctness.
              // Clamp the threshold to a minimum of 1. A non-positive `b2AdmissionAtTipStreak`
              // would silently satisfy `streaks.getOrElse(pid, 0) >= 0` for every probation
              // peer on the very first tick, restoring the pre-fix one-shot behavior without
              // any signal at config load time. Forcing a floor of 1 means mis-configured
              // values degrade gracefully to the old-style immediate admission, which at least
              // matches behavior prior to this fix rather than silently bypassing the streak.
              val minStreak = math.max(1, config.b2AdmissionAtTipStreak)
              val readyAtTip: Set[PeerId] =
                if (!probationAdmissionVoteAllowed) Set.empty
                else if (admissionCandidateTipProbe.nonEmpty)
                  AdmissionCandidateTipProbe.readyProbationTarget(
                    fixedProbationTarget,
                    probationObservation,
                    streaks,
                    minStreak,
                    alreadyVotedBySelf,
                    expectedTip,
                    expectedOrdinal
                  )
                else
                  probation.filter { pid =>
                    !alreadyVotedBySelf.contains(pid) && streaks.getOrElse(pid, 0) >= minStreak
                  }
              val readyCandidatesAtTip: List[PeerId] =
                if (admissionCandidateTipProbe.nonEmpty)
                  AdmissionCandidateTipProbe
                    .readyOpenTarget(
                      openProbeTarget,
                      openObservation,
                      hasCurrentRoundFacility,
                      expectedTip,
                      expectedOrdinal
                    )
                    .toList
                else openAdmissionTargets.filter(pid => chainTips.get(pid).exists(isAdmissionReadyTip))
              val openCandidateExactTip = openObservation match {
                case AdmissionCandidateTipProbe.Observation.Attempted(Some(tip)) =>
                  AdmissionTipReadiness.isExact(tip, expectedTip, expectedOrdinal)
                case _ => false
              }
              val openCandidateAlignmentOutcome =
                openAlignmentOutcomes.headOption
                  .map(_._2)
                  .orElse {
                    fixedOpenTarget.filterNot(hasCurrentRoundFacility).as("waiting_for_current_facility")
                  }
                  .getOrElse("not_applicable")
              // Admission-gate diagnostic log per probation peer per tick.
              // Follow-up to the alpha.50 ZERO-admission-certs finding: lets operators verify
              // which gate is the actual blocker -- empty probation, atTip false, streak < minStreak,
              // or already-voted-by-self. One INFO line per call when probation is non-empty.
              val atTipPerPeer = probation.iterator.map { pid =>
                val atTip =
                  if (admissionCandidateTipProbe.nonEmpty)
                    directProbeResults.exists {
                      case (target, lane, Some(tip)) =>
                        lane.isProbationRecovery && target === pid && AdmissionTipReadiness.isExact(tip, expectedTip, expectedOrdinal)
                      case _ => false
                    }
                  else chainTips.get(pid).exists(isAdmissionReadyTip)
                val streak = streaks.getOrElse(pid, 0)
                val voted = alreadyVotedBySelf.contains(pid)
                (pid, atTip, streak, voted)
              }.toList.sortBy(_._1.toString)
              val atTipCount = atTipPerPeer.count(_._2)
              val probationProbeDisposition =
                if (admissionCandidateTipProbe.isEmpty) "disabled"
                else if (fixedProbationTarget.isEmpty) "no_target"
                else if (fixedProbationTarget.exists(alreadyVotedBySelf.contains)) "already_voted"
                else if (!probationAdmissionVoteAllowed) "policy_blocked"
                else if (!probationProbeDue) "throttled"
                else
                  probationObservation match {
                    case AdmissionCandidateTipProbe.Observation.Attempted(Some(tip))
                        if AdmissionTipReadiness.isExact(tip, expectedTip, expectedOrdinal) =>
                      "ready"
                    case AdmissionCandidateTipProbe.Observation.Attempted(Some(_)) => "not_ready"
                    case AdmissionCandidateTipProbe.Observation.Attempted(None)    => "unavailable"
                    case AdmissionCandidateTipProbe.Observation.NotAttempted       => "not_attempted"
                  }
              val details = atTipPerPeer.map {
                case (pid, atTip, streak, voted) =>
                  s"${pid.show.take(8)}:atTip=$atTip,streak=$streak,votedBySelf=$voted"
              }
                .mkString(",")
              ConsensusLog
                .info(
                  logger,
                  Category.Facilitator,
                  key.toString,
                  "n/a",
                  LogEvent.Admission,
                  "stage" -> "gate",
                  "probation" -> probation.size.toString,
                  "atTip" -> atTipCount.toString,
                  "ready" -> readyAtTip.size.toString,
                  "canonicalNominees" -> canonicalNominees.size.toString,
                  "openTargets" -> openAdmissionTargets.size.toString,
                  "candidateReady" -> readyCandidatesAtTip.size.toString,
                  "candidateExactTip" -> openCandidateExactTip.toString,
                  "candidateCurrentFacility" -> openCandidateCurrentFacility.toString,
                  "candidateAlignmentOutcome" -> openCandidateAlignmentOutcome,
                  "candidateVoteQuorum" -> voteQuorum.toString,
                  "openCadenceAllowed" -> openAdmissionPolicy.cadenceAllowed.toString,
                  "openVoteAllowed" -> openAdmissionPolicy.allowsOpenAdmission.toString,
                  "atomicReplacementVoteAllowed" -> atomicReplacementAdmissionAllowed.toString,
                  "probationVoteAllowed" -> probationAdmissionVoteAllowed.toString,
                  "openPolicyOutcome" -> policyOutcome,
                  "openObservedSigners" -> openAdmissionPolicy.headroom
                    .map(_.observedCurrentCommitteeSigners.toString)
                    .getOrElse("n/a"),
                  "openNextFinalityFloor" -> openAdmissionPolicy.headroom.map(_.nextFinalityFloor.toString).getOrElse("n/a"),
                  "openFinalityMargin" -> openAdmissionPolicy.headroom.map(_.margin.toString).getOrElse("n/a"),
                  "sustainedGateActive" -> openAdmissionPolicy.sustainedHeadroom
                    .exists(_.raisesFinalityFloor)
                    .toString,
                  "sustainedHistoryDepth" -> openAdmissionPolicy.sustainedHeadroom
                    .map(_.observedParents.toString)
                    .getOrElse("n/a"),
                  "sustainedHistoryRequired" -> openAdmissionPolicy.sustainedHeadroom
                    .map(_.requiredParents.toString)
                    .getOrElse("n/a"),
                  "sustainedQualifyingParents" -> openAdmissionPolicy.sustainedHeadroom
                    .map(_.qualifyingParents.toString)
                    .getOrElse("n/a"),
                  "minStreak" -> minStreak.toString,
                  "tipLagTolerance" -> AdmissionTipReadiness.OrdinalLagTolerance.toString,
                  "expectedOrdinal" -> expectedOrdinal.map(_.value.value.toString).getOrElse("none"),
                  "directProbeTarget" -> directProbeResults.map { case (target, _, _) => target.show.take(8) }.mkString(","),
                  "directProbeLane" -> directProbeResults.map { case (_, lane, _) => lane.label }.mkString(","),
                  "directProbeOutcome" -> directProbeOutcomes.map(_._3).mkString(","),
                  "probationProbeDisposition" -> probationProbeDisposition,
                  "candidateTipOrdinals" -> openAdmissionTargets
                    .flatMap(pid => chainTips.get(pid).map(tip => s"${pid.show.take(8)}:${tip.ordinal.value.value}"))
                    .mkString(","),
                  "details" -> details
                ) >> {
                val emissionTargets = StallDetector.admissionVoteTargets(
                  probationReady = readyAtTip.toList,
                  openReady = readyCandidatesAtTip,
                  maxOpenAdmissions = configuredMaxAdmissions,
                  laneProbesEnabled = admissionCandidateTipProbe.nonEmpty
                )
                emissionTargets.traverse_ { target =>
                  admissionVoter.emitAdmissionVote(key, target, AdmissionReason.ReadyAtTip) >>
                    queue.offer(ConsensusCommand.CheckAdmissionAssembly(key, target))
                }
              }.as(AdmissionVoteEmission(nextProbedTargets, probationObservation))
            }
          }
      }
  }
}

object StallDetector {

  /** True only when `target` has entered the same current round as this Core voter.
    *
    * `ConsensusResources` is already scoped by consensus key, but Facility has no view field and storage is latest-write-wins across view
    * changes. Comparing the target's parent, facilitator binding, and deterministic-config fingerprint with the voter's own current
    * Facility therefore proves the strongest schema-compatible alignment available. The target still needs a fresh exact metadata response
    * before admission voting; neither observation is consensus state on its own.
    */
  private[consensus] def hasCurrentRoundFacility(
    declarations: Map[PeerId, PeerDeclarations],
    voter: PeerId,
    target: PeerId,
    expectedParentHash: Hash,
    expectedParentOrdinal: Option[SnapshotOrdinal]
  ): Boolean = {
    def isExpectedParent(facility: declaration.Facility): Boolean =
      facility.lastSnapshotHash === expectedParentHash &&
        expectedParentOrdinal.exists(_ === facility.lastGlobalSnapshotOrdinal)

    (
      declarations.get(voter).flatMap(_.facility),
      declarations.get(target).flatMap(_.facility)
    ).mapN { (voterFacility, targetFacility) =>
      isExpectedParent(voterFacility) &&
      isExpectedParent(targetFacility) &&
      targetFacility.facilitatorsHash === voterFacility.facilitatorsHash &&
      voterFacility.consensusConfigHash.nonEmpty &&
      targetFacility.consensusConfigHash === voterFacility.consensusConfigHash
    }.getOrElse(false)
  }

  /** Advance the bounded, node-local parent-proof history at a real monitor/round boundary.
    *
    * Global L0 supplies both proof signers and a snapshot ordinal. A layer that supplies proof signers without an ordinal fails closed by
    * clearing history. Currency L0 supplies no proof view, so this path is inert and its cadence-only admission policy remains unchanged.
    */
  private[consensus] def observeAdmissionProofHistory[F[_]: Sync](
    admissionProofHistoryRef: Ref[F, AdmissionProofHistory.History],
    locallyObservedParentSigners: Option[Set[PeerId]],
    parentOrdinal: Option[Long],
    parentHash: Hash
  ): F[Option[AdmissionProofHistory.History]] =
    (locallyObservedParentSigners, parentOrdinal) match {
      case (Some(signers), Some(ordinal)) =>
        admissionProofHistoryRef.modify { previous =>
          val next = AdmissionProofHistory.observe(previous, ordinal, parentHash, signers)
          (next, next.some)
        }
      case (Some(_), None) =>
        // A layer that opts into actual-proof gating but cannot identify a snapshot ordinal
        // fails closed until a valid lineage is available.
        admissionProofHistoryRef.set(AdmissionProofHistory.History.empty).as(AdmissionProofHistory.History.empty.some)
      case (None, _) => none[AdmissionProofHistory.History].pure[F]
    }

  /** Give a newly accepted pacemaker request exactly one serialized-loop opportunity before a local abandon may remove the state it needs
    * for certificate assembly. Duplicate requests return `newPacemakerRequestEnqueued = false`, so an infeasible transition escapes via the
    * ordinary abandon path on the following monitor tick.
    */
  private[consensus] def shouldAbandonThisMonitorTick(
    abandonRequested: Boolean,
    isLagging: Boolean,
    sameKeyRestartUnsafe: Boolean,
    newPacemakerRequestEnqueued: Boolean
  ): Boolean =
    abandonRequested && (isLagging || !sameKeyRestartUnsafe) && !newPacemakerRequestEnqueued

  /** Under the Global L0 fail-closed bridge, a same-key abandon/recreate is safe only before proposal acceptance and before this node has
    * voted or entered a certified later view. Currency explicitly keeps rc.7's legacy retry policy until it receives a coordinated
    * full-value-QC rollout of its own.
    */
  private[consensus] def sameKeyRestartUnsafe(
    viewNumber: Int,
    phaseIndex: Int,
    voteLockPopulated: Boolean,
    mode: ViewSafetyMode
  ): Boolean =
    mode == ViewSafetyMode.LegacyFreezeAfterVote && (viewNumber > 0 || phaseIndex >= 2 || voteLockPopulated)

  /** Select the exact local atomic-replacement intents that may open the admission-vote lane.
    *
    * The signing-finality auditor emits at most one deterministic `Silent` vote per round. An assembled ECS is deliberately not required
    * here because certificates are not independently gossiped: on asymmetric delivery every honest Core node can hold its own valid vote
    * without any one of them holding the quorum set. The locally stored self vote is sufficient only to emit the paired ACS vote; proposal
    * construction and validation still require quorum-certified ECS + ACS with equal cardinality.
    *
    * Every payload binding is checked rather than merely looking for `selfId` in the voter map. In particular, stale votes from another
    * parent or committee cannot authorize the bypass, and a generic stall-path vote for a different peer cannot be mistaken for the
    * rendezvous-selected auditor target.
    */
  private[consensus] def atomicReplacementIntentTargets(
    selfId: PeerId,
    selfIsCore: Boolean,
    atomicReplacementEnabled: Boolean,
    cadenceAllowed: Boolean,
    currentCommittee: Set[PeerId],
    parentRoundCommittee: Set[PeerId],
    selfEvictionVotes: Map[PeerId, Signed[EvictionVote]],
    expectedFacilitatorsHash: Hash,
    expectedParentHash: Hash,
    entropy: Hash,
    maxTargets: Int
  ): List[PeerId] =
    if (!atomicReplacementEnabled || !selfIsCore || !cadenceAllowed || maxTargets <= 0) List.empty
    else
      FinalityParticipationAuditor
        .selectTarget(currentCommittee, parentRoundCommittee, entropy)
        .filter { target =>
          target =!= selfId &&
          currentCommittee.contains(target) &&
          selfEvictionVotes.get(target).exists { signed =>
            val signerIds = signed.proofs.toSortedSet.iterator.map(_.id.toPeerId).toSet
            val vote = signed.value

            signerIds === Set(selfId) &&
            vote.targetPeer === target &&
            vote.reason === EvictionReason.Silent &&
            vote.facilitatorsHash === expectedFacilitatorsHash &&
            vote.lastSnapshotHash === expectedParentHash
          }
        }
        .toList
        .take(math.max(0, maxTargets))

  /** An atomic replacement cannot remove and re-admit the same identity. Keep the exclusion in one pure helper so proposal-nominee input
    * cannot accidentally bypass it during future lane refactors.
    */
  private[consensus] def excludeAtomicReplacementTargets(
    candidates: Set[PeerId],
    replacementTargets: Iterable[PeerId]
  ): Set[PeerId] =
    candidates -- replacementTargets.toSet

  /** Keep probation recovery and open expansion as independent vote-emission lanes.
    *
    * Global L0's direct probes produce at most one fixed probation target plus the configured number of fixed open targets. Currency's
    * no-probe path retains rc.6 behavior exactly: every locally ready probation/open target may emit, while proposal construction remains
    * the shared certificate cap.
    */
  private[consensus] def admissionVoteTargets(
    probationReady: List[PeerId],
    openReady: List[PeerId],
    maxOpenAdmissions: Int,
    laneProbesEnabled: Boolean
  ): List[PeerId] =
    if (laneProbesEnabled) {
      val probation = probationReady.distinct
      probation ++ openReady.filterNot(probation.toSet).distinct.take(math.max(0, maxOpenAdmissions))
    } else (probationReady ++ openReady).distinct

  /** Select the fixed open-expansion targets a Core voter may consider in one round.
    *
    * The configured budget is applied before already-voted targets are removed. This ordering is load-bearing: applying `take` after
    * removal turns each monitor tick into a cursor over the candidate list and lets a budget of one emit one vote per tick. The fixed
    * prefix means a voter that already voted for its target is done for the round, and a voter that cannot locally verify that target
    * abstains instead of walking to a different peer.
    *
    * Candidate order is rendezvous-hashed from the accepted parent snapshot hash, with PeerId as the final tie-break. Candidate input order
    * therefore cannot affect the result.
    */
  private[consensus] def openAdmissionTargets(
    candidates: Iterable[PeerId],
    committee: Set[PeerId],
    probation: Set[PeerId],
    alreadyVotedBySelf: Set[PeerId],
    entropy: Hash,
    maxOpenAdmissions: Int,
    selfIsCore: Boolean
  ): List[PeerId] = {
    val budget = math.max(0, maxOpenAdmissions)
    val openVotesAlreadyUsed = (alreadyVotedBySelf -- probation).size
    val remainingBudget = math.max(0, budget - openVotesAlreadyUsed)

    if (!selfIsCore || remainingBudget == 0) List.empty
    else {
      implicit val scoreOrder: Order[PeerId] = FacilitatorSelector.orderByScore(entropy)
      val fixedRoundTargets = candidates.iterator
        .filterNot(pid => committee.contains(pid) || probation.contains(pid))
        .toList
        .distinct
        .sorted(scoreOrder.toOrdering)
        .take(budget)

      fixedRoundTargets.filterNot(alreadyVotedBySelf.contains).take(remainingBudget)
    }
  }

  /** Linear increment applied to `maxRoundDuration` per view number when computing the per-view effective round deadline. Combined with
    * resetting `roundStartTime` on view change, this gives each view a fresh budget that grows slightly with view to reflect progressively
    * worse network conditions. Capped at `2 * base` by `maxRoundDurationForView`. 90s matches ~1.5 stall cycles — one full stall-detect
    * round plus slack for gossip jitter.
    */
  private[consensus] val PerViewRoundDurationIncrement: FiniteDuration = 90.seconds

  /** Hard cap on stored-Facility retransmits per round. Past this count the round is likely stuck on issues other than facility-delivery
    * (genuinely-absent peers, network partition, etc.) and additional retransmits are wasted.
    *
    * Raised 3 -> 5 to match the new capped-exponential cadence (see `nextRetransmitDelay`). The first 5 retransmits fire within
    * 5+10+20+30+30 = 95s, which is the same wall-time budget as the previous fixed-30s x 3 = 90s but lands the early attempts an order of
    * magnitude sooner -- catching gossip-jitter Facility drops in the first 35s instead of waiting 90s.
    */
  private[consensus] val MaxFacilityRetransmits: Int = 5

  /** Number of stall cycles to keep the gossip-tip / cluster-responsiveness shield active before evicting still-missing peers. See the
    * comment block at the use site (eviction grace period) for full rationale.
    */
  private[consensus] val EvictionSkipMaxStalls: Int = 3

  /** Initial delay before the first Facility retransmit, and the exponential base for subsequent attempts. Chosen at 5s to fire fast enough
    * to catch the common case (gossip-mesh drop during cold-start round 1-10 on docker compose) without piling on bandwidth when peers are
    * healthy.
    */
  private[consensus] val FacilityRetransmitInitialDelay: FiniteDuration = 5.seconds

  /** Cap on the exponential backoff. Once the schedule reaches this value it stays here for the remaining attempts. Matches the previous
    * fixed cadence (~declarationTimeout) so steady-state behaviour is unchanged when the issue persists past the first few retries.
    */
  private[consensus] val FacilityRetransmitMaxDelay: FiniteDuration = 30.seconds

  /** Compute the wall-clock delay required between attempt `n` and attempt `n+1` of a Facility retransmit. Capped exponential: 5s -> 10s ->
    * 20s -> 30s -> 30s -> ...
    *
    * Pure function, exposed for unit-tests.
    */
  private[consensus] def nextRetransmitDelay(attempt: Int): FiniteDuration =
    if (attempt < 0) FacilityRetransmitInitialDelay
    else {
      // Bound the doubling exponent to avoid overflow. Once raw exceeds the cap we clamp anyway.
      val safeExponent = math.min(attempt.toLong, 20L)
      val rawMillis = FacilityRetransmitInitialDelay.toMillis * (1L << safeExponent)
      val capMillis = FacilityRetransmitMaxDelay.toMillis
      FiniteDuration(math.min(rawMillis, capMillis), MILLISECONDS)
    }

  /** Outcome of the Core-only quorum infeasibility check performed inside `handleStall`. Pure data so the gate can be exercised in unit
    * tests without a live consensus context.
    */
  private[consensus] case class CoreQuorumStatus(
    coreSize: Int,
    coreRemaining: Int,
    coreRequired: Int,
    // Unshrunk supermajority (before any quorum-denominator-shrink override). The eviction cap MUST be
    // derived from this, never from the shrunk `coreRequired`, so a liveness shrink cannot raise the cap
    // and let the committee be voted below the supermajority safety budget (cap = committee.size - f).
    baseRequired: Int,
    quorumInfeasible: Boolean
  )

  /** Pure helper for the alpha.91 Core-only quorum-infeasibility gate.
    *
    * Mirrors the inline computation in `handleStall`: from `activeCore` and `missingPeers` (the full-facilitator missing set, which we
    * intersect with Core), produce the `(coreSize, coreRemaining, coreRequired, quorumInfeasible)` tuple that drives the abandon decision.
    * Tier 1 peers outside Core appear in `missingPeers` for observability but do NOT affect the gate, matching
    * `ConsensusStateAdvancer.maybeGetAllDeclarations` (alpha.89) and `StateTransitions.checkViewChangeAssembly` (alpha.89).
    *
    * Pre-alpha.91 the gate was `state.facilitators.value.size - missingPeers.size <
    * QuorumPolicy.fromFraction(state.facilitators.value.size, fraction)`, which abandoned rounds with healthy Core (3/3) when Tier 1 was
    * silent -- observed post-alpha.90 at ord 3127058. Codex flagged this as task #123 in the user tracker.
    *
    * Exposed for unit testing; the production gate inlines the same arithmetic.
    */
  private[consensus] def computeCoreQuorumStatus(
    activeCore: Set[PeerId],
    missingPeers: Set[PeerId],
    quorumThresholdFraction: Double,
    // v33 quorum-denominator shrink: when the escalated rung is live, the effective required
    // quorum (never above the base Core quorum) replaces the base in the feasibility gate so
    // the detector stops abandoning rounds the shrunken quorum can actually close.
    quorumOverride: Option[Int] = None
  ): CoreQuorumStatus = {
    val coreSize = activeCore.size
    val missingCore = activeCore.intersect(missingPeers)
    val coreRemaining = coreSize - missingCore.size
    val baseRequired = math.max(1, QuorumPolicy.fromFraction(coreSize, quorumThresholdFraction))
    val coreRequired = quorumOverride.fold(baseRequired)(o => math.min(baseRequired, math.max(1, o)))
    CoreQuorumStatus(
      coreSize = coreSize,
      coreRemaining = coreRemaining,
      coreRequired = coreRequired,
      baseRequired = baseRequired,
      quorumInfeasible = coreRemaining < coreRequired
    )
  }

  /** Outcome of the alpha.98 ready-participation feasibility check. Pure data so the gate can be exercised in unit tests without a live
    * consensus context. `behindNonReady` is a diagnostic subset (count of `notReadyCore` peers whose observed tip is also behind our last
    * outcome key); it does not affect the abandon decision -- that depends only on `notReadyCore`.
    */
  private[consensus] case class ReadyParticipationStatus(
    coreSize: Int,
    activeReady: Int,
    coreQuorum: Int,
    notReadyCore: Int,
    behindNonReady: Int,
    infeasible: Boolean
  )

  /** Pure helper for the alpha.98 ready-participation feasibility check. From the Core committee, the cluster's currently-Ready peer ids,
    * self's id, and per-peer observed tip keys, produce the (activeReady, coreQuorum, notReadyCore, behindNonReady, infeasible) tuple that
    * drives the new abandon path in `StallDetector`.
    *
    * Codex's v2 review notes the two subtle invariants this helper locks down:
    *   1. self MUST be counted as Ready/current. `getResponsivePeers` does not return self; checking `!readyPeerIds.contains(selfId)`
    *      against the raw responsive-peers set would classify self as not-Ready and false-abandon healthy rounds (most damaging in 2-Core
    *      rounds where self + 1 peer == quorum). Caller adds `selfId` to `readyPeerIds` before invoking this helper. 2. The exclusion test
    *      is ONLY `not in Ready`. A peer that is WaitingForReady-but-caught-up still cannot sign or lead the round, so treating "caught up"
    *      as "Ready enough" misses the WFR-promotion-starvation wedge. The `behindNonReady` field is recorded only as a diagnostic
    *      dimension for the log line.
    *
    * Local-guard semantics: this helper does NOT modify the committee, facilitator hash, or quorum derivation. It only computes whether the
    * local observation supports proceeding with the round. Honest peers may disagree on the result based on their own gossip-derived Ready
    * sets; that disagreement only changes WHEN each peer abandons locally, never WHAT the committee looks like.
    */
  private[consensus] def computeReadyParticipationStatus(
    coreFacilitators: Set[PeerId],
    readyPeerIds: Set[PeerId],
    selfId: PeerId,
    peerCurrentKeysContains: PeerId => Boolean,
    peerCurrentKeyAtOrAfter: PeerId => Boolean,
    quorumThresholdFraction: Double,
    // v33 quorum-denominator shrink: effective required quorum when the escalated rung is
    // live (see `computeCoreQuorumStatus`). Without this, the detector keeps firing
    // `ready_participation_quorum_infeasible` and abandons the very rounds the shrunken
    // quorum could close -- the live ord-3150197 loop.
    quorumOverride: Option[Int] = None
  ): ReadyParticipationStatus = {
    val coreSize = coreFacilitators.size
    val readyPeerIdsWithSelf = readyPeerIds + selfId
    val notReadyCore = coreFacilitators.filterNot(readyPeerIdsWithSelf.contains)
    val behindNonReady = notReadyCore.count { peerId =>
      !peerCurrentKeysContains(peerId) || !peerCurrentKeyAtOrAfter(peerId)
    }
    val activeReady = coreSize - notReadyCore.size
    val baseQuorum = math.max(1, QuorumPolicy.fromFraction(coreSize, quorumThresholdFraction))
    val coreQuorum = quorumOverride.fold(baseQuorum)(o => math.min(baseQuorum, math.max(1, o)))
    ReadyParticipationStatus(
      coreSize = coreSize,
      activeReady = activeReady,
      coreQuorum = coreQuorum,
      notReadyCore = notReadyCore.size,
      behindNonReady = behindNonReady,
      infeasible = coreSize >= 2 && activeReady < coreQuorum
    )
  }

  /** Deterministic selection of eviction-vote targets for a single node emission pass.
    *
    * Extracted as a pure helper so the correctness property (same subset chosen by every honest node when `unresponsiveMissing.size >
    * remainingSlots`) is testable without running the full stall-detector monitor.
    *
    * Invariants the caller relies on:
    *
    *   1. Result is a subset of `unresponsiveMissing ∩ committee`, minus `alreadyVotedBySelf`, minus `{selfId}`. 2. `selfId` is NEVER in
    *      the result — clusterStorage does not track self as Responsive, so `missingPeers - responsivePeers` ALWAYS includes self when self
    *      hasn't yet posted the declaration for the current phase. Without excluding self here, a node would emit a vote to evict itself
    *      whenever a phase-transition stall detector fires before the node has locally posted its declaration. Observed in E2E at round 5
    *      bootstrap: gl0-0 emitted `Signed[EvictionVote(targetPeer=gl0-0)]` to 3 gossip targets. 3. Result size is at most `max(0,
    *      committee.size - minQuorum) - alreadyVotedBySelf.size`, bounded below by 0. The cap equals the byzantine fault tolerance `f = n -
    *      quorum`: for n=3f+1, quorum=2f+1, the cap is exactly f. Earlier versions used `ceil(committee.size / 3)` which slightly
    *      over-counts (allows f+1 evictions on n=9), letting the aggregate of honest-voter agreed targets exceed `committee - quorum` and
    *      driving the round into `QUORUM_INFEASIBLE_EVICTION` even when honest voters only emitted within their per-voter caps. The
    *      quorum-derived cap guarantees that no aggregate set of cert-finalized evictions can shrink the committee below quorum. Observed
    *      Observed: post-restart 9-committee deadlocked when 5+ FACILITY_FOREVER cohort peers in the committee triggered eviction votes;
    *      with the old cap of 3 every honest voter agreed on the same 3 canonical-prefix targets, drove cert assembly to shrink committee
    *      below the 7-of-9 quorum, and broke liveness for hours. 4. Ordering is stable: for the same inputs (as Sets), this function
    *      returns the same list in the same order every invocation. This is what lets different honest nodes vote for the same subset when
    *      more peers are missing than any one voter's cap allows — otherwise cert quorum would starve (codex review finding #2).
    */
  private[consensus] def selectEvictionTargets(
    selfId: PeerId,
    unresponsiveMissing: Set[PeerId],
    committee: Set[PeerId],
    alreadyVotedBySelf: Set[PeerId],
    minQuorum: Int
  ): List[PeerId] = {
    // Quorum-aware cap: at most (committee.size - minQuorum) evictions can be
    // certified before the next-round committee falls below quorum. With the
    // canonical sort, all honest voters select the same prefix of length cap,
    // so cert assembly converges on exactly that prefix and no more.
    val cap = math.max(0, committee.size - minQuorum)
    val remainingSlots = (cap - alreadyVotedBySelf.size).max(0)
    if (remainingSlots == 0) List.empty
    else
      unresponsiveMissing
        .filter(committee.contains)
        .filterNot(alreadyVotedBySelf.contains)
        .filterNot(_ === selfId) // never vote to evict ourselves
        .toList
        .sortBy(_.value.value) // canonical hex identity — same on every node
        .take(remainingSlots)
  }

}
