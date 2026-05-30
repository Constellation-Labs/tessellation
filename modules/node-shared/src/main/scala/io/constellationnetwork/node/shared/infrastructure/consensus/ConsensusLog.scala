package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Applicative
import cats.syntax.all._

import io.constellationnetwork.schema.peer.PeerId

import org.typelevel.log4cats.SelfAwareStructuredLogger

/** Thin structured-logging helper for consensus.
  *
  * Every log line produced by this helper has the form:
  * {{{
  *   [CONSENSUS:<category>] round=<key> role=<role> event=<event> k1=v1 k2=v2 ...
  * }}}
  *
  * This makes it trivial to:
  *   - `grep 'round=42'` to see every log for one consensus round
  *   - `grep 'role=Leader'` to isolate leader-only activity
  *   - `grep 'event=VALIDATION_FAILED'` to find fork-triggering mismatches
  *
  * ==Usage==
  * {{{
  *   import ConsensusLog.Event._
  *   import ConsensusLog.Category._
  *
  *   ConsensusLog.info(logger, Phase, key.show, role, FacilitiesToProposals,
  *     "trigger" -> trigger.show,
  *     "facilitators" -> facilitators.size.toString)
  * }}}
  *
  * ==Design==
  *
  * Stateless, pure formatting. No implicits beyond `Applicative[F]` for the logger. Accepts only `(String, String)*` pairs to prevent
  * accidentally logging large objects (artifacts, contexts, full state).
  */
object ConsensusLog {

  // ── Log categories ──────────────────────────────────────────────

  /** Type-safe log categories for consensus logging. */
  sealed trait Category {
    def show: String
  }
  object Category {
    case object Lifecycle extends Category { val show = "LIFECYCLE" }
    case object Phase extends Category { val show = "PHASE" }
    case object Stall extends Category { val show = "STALL" }
    case object Quorum extends Category { val show = "QUORUM" }
    case object Fork extends Category { val show = "FORK" }
    case object Facilitator extends Category { val show = "FACILITATOR" }
    case object Proposal extends Category { val show = "PROPOSAL" }
    case object Validation extends Category { val show = "VALIDATION" }
    case object Recovery extends Category { val show = "RECOVERY" }
    case object Rumor extends Category { val show = "RUMOR" }
  }

  // ── Consensus events ────────────────────────────────────────────

  /** Type-safe consensus event names. Prevents typos and enables compile-time checking. */
  sealed trait Event {
    def show: String
  }
  object Event {

    // ── Round lifecycle events ────────────────────────────────────
    case object RoundStarted extends Event { val show = "ROUND_STARTED" }
    case object RoundFacilitating extends Event { val show = "ROUND_FACILITATING" }
    case object RoundFacilitated extends Event { val show = "ROUND_FACILITATED" }
    case object RoundCompleted extends Event { val show = "ROUND_COMPLETED" }
    case object RoundCompletedNoOutcome extends Event { val show = "ROUND_COMPLETED_NO_OUTCOME" }
    case object RoundAbandoned extends Event { val show = "ROUND_ABANDONED" }
    case object RoundAbandonedRetriable extends Event { val show = "ROUND_ABANDONED_RETRIABLE" }
    case object RetriableEscalated extends Event { val show = "RETRIABLE_ESCALATED" }
    case object RoundAbandonedTracked extends Event { val show = "ROUND_ABANDONED_TRACKED" }
    case object RoundBlockedByState extends Event { val show = "ROUND_BLOCKED_BY_STATE" }
    case object RoundMonitor extends Event { val show = "ROUND_MONITOR" }
    case object FsmRoundStart extends Event { val show = "FSM_ROUND_START" }
    case object ConsensusFinished extends Event { val show = "CONSENSUS_FINISHED" }
    case object IdleRoundCompleted extends Event { val show = "IDLE_ROUND_COMPLETED" }
    case object IdleConsensusFinished extends Event { val show = "IDLE_CONSENSUS_FINISHED" }

    // ── State events ──────────────────────────────────────────────
    case object StateCreated extends Event { val show = "STATE_CREATED" }
    case object StateExists extends Event { val show = "STATE_EXISTS" }
    case object StateUpdated extends Event { val show = "STATE_UPDATED" }
    case object NoState extends Event { val show = "NO_STATE" }
    case object NoPreviousOutcome extends Event { val show = "NO_PREVIOUS_OUTCOME" }
    case object InitialCheck extends Event { val show = "INITIAL_CHECK" }

    // ── Phase transition events ───────────────────────────────────
    case object FacilitiesToProposals extends Event { val show = "FACILITIES_TO_PROPOSALS" }
    case object ProposalsToSignatures extends Event { val show = "PROPOSALS_TO_SIGNATURES" }
    case object SignaturesToFinished extends Event { val show = "SIGNATURES_TO_FINISHED" }

    // ── Facilitator events ────────────────────────────────────────
    case object FacilitatorsFinalized extends Event { val show = "FACILITATORS_FINALIZED" }
    case object FacilitatorSubsetting extends Event { val show = "FACILITATOR_SUBSETTING" }
    case object MinQuorumFloorApplied extends Event { val show = "MIN_QUORUM_FLOOR_APPLIED" }
    case object CandidateObserving extends Event { val show = "CANDIDATE_OBSERVING" }
    case object ChronicNonSignersExcluded extends Event { val show = "CHRONIC_NON_SIGNERS_EXCLUDED" }
    // Active-set tightening based on the recent-signers window. Logged at round-start
    // when the M-of-K filter is evaluated. The `filterApplied` field distinguishes
    // whether the filter actually excluded peers or fell through to the bootstrap/floor
    // fallback.
    case object ActiveSetTightened extends Event { val show = "ACTIVE_SET_TIGHTENED" }
    case object TcaFilterApplied extends Event { val show = "TCA_FILTER_APPLIED" }
    case object AbandonedMissingLogged extends Event { val show = "ABANDONED_MISSING_LOGGED" }
    case object PriorRoundMissingExcluded extends Event { val show = "PRIOR_ROUND_MISSING_EXCLUDED" }
    case object FacilityRetransmit extends Event { val show = "FACILITY_RETRANSMIT" }

    // ── Proposal events ───────────────────────────────────────────
    case object ProposalEvents extends Event { val show = "PROPOSAL_EVENTS" }
    case object ProposalRespread extends Event { val show = "PROPOSAL_RESPREAD" }
    case object ProposalStateProof extends Event { val show = "PROPOSAL_STATE_PROOF" }
    case object ProposalContextDigest extends Event { val show = "PROPOSAL_CONTEXT_DIGEST" }
    case object OwnContextDigest extends Event { val show = "OWN_CONTEXT_DIGEST" }

    // ── Artifact events ───────────────────────────────────────────
    case object ArtifactBuilt extends Event { val show = "ARTIFACT_BUILT" }
    case object ArtifactHashMatch extends Event { val show = "ARTIFACT_HASH_MATCH" }
    case object ArtifactRevalidated extends Event { val show = "ARTIFACT_REVALIDATED" }
    case object MajorityArtifactAbandoned extends Event { val show = "MAJORITY_ARTIFACT_ABANDONED" }
    case object MajorityArtifactFallback extends Event { val show = "MAJORITY_ARTIFACT_FALLBACK" }

    // ── Validation events ─────────────────────────────────────────
    case object ValidatingLeaderArtifact extends Event { val show = "VALIDATING_LEADER_ARTIFACT" }
    case object ValidationFailed extends Event { val show = "VALIDATION_FAILED" }
    case object WithdrawValidationFail extends Event { val show = "WITHDRAW_VALIDATION_FAIL" }
    case object Withdrew extends Event { val show = "WITHDREW" }

    // ── Acceptance events ─────────────────────────────────────────
    case object AcceptTiming extends Event { val show = "ACCEPT_TIMING" }
    case object AcceptanceResults extends Event { val show = "ACCEPTANCE_RESULTS" }

    // ── Fork detection and handling events ────────────────────────
    case object ForkDetected extends Event { val show = "FORK_DETECTED" }
    case object ForkChecksPassed extends Event { val show = "FORK_CHECKS_PASSED" }
    case object ForkRecoveryFailed extends Event { val show = "FORK_RECOVERY_FAILED" }
    case object ForkedPeersEvicted extends Event { val show = "FORKED_PEERS_EVICTED" }
    case object OutcomeConflict extends Event { val show = "OUTCOME_CONFLICT" }

    // ── View change and eviction events ───────────────────────────
    case object ViewChange extends Event { val show = "VIEW_CHANGE" }
    case object ViewChangeWithEviction extends Event { val show = "VIEW_CHANGE_WITH_EVICTION" }
    case object EarlyViewChange extends Event { val show = "EARLY_VIEW_CHANGE" }
    // Defensive force-VCV emission when consecutiveAbandonments at the same
    // ordinal crosses `forceViewChangeAbandonments`. Bypasses the per-round "missing-still-
    // responsive" gate in StallDetector that otherwise blocks VCV emission across abandoned
    // rounds (the gate is correct for the FIRST stall but wrong when applied across N abandons
    // at the same ord). All responsive peers cross the threshold within bounded skew and emit
    // VCVs at the same (fromView, toView), letting VCC assembly converge.
    case object ForcedViewChange extends Event { val show = "FORCED_VIEW_CHANGE" }
    case object EvictionLoopEscalation extends Event { val show = "EVICTION_LOOP_ESCALATION" }
    case object EvictionSkippedMinFacilitators extends Event { val show = "EVICTION_SKIPPED_MIN_FACILITATORS" }
    // Phase B1 EvictionVote mechanism (see codex-handoff-facilitator-set-shrinkage.md):
    // signed negative-evidence votes for persistently-absent facilitators, assembled into
    // an EvictionCertificate at quorum and embedded in the next Proposal.
    case object Eviction extends Event { val show = "EVICTION" }
    // Phase B2 AdmissionVote mechanism: symmetric counterpart — signed positive-evidence
    // votes for previously-removed peers observed at tip, assembled into an
    // AdmissionCertificate at quorum and embedded in the next Proposal.
    case object Admission extends Event { val show = "ADMISSION" }

    // ── Stall detection events ────────────────────────────────────
    case object StallDetected extends Event { val show = "STALL_DETECTED" }
    case object LeaderStall extends Event { val show = "LEADER_STALL" }
    case object LaggingNodeDetected extends Event { val show = "LAGGING_NODE_DETECTED" }
    case object PeerStallWarning extends Event { val show = "PEER_STALL_WARNING" }
    case object PeerQuality extends Event { val show = "PEER_QUALITY" }
    case object RecordingMissingPeers extends Event { val show = "RECORDING_MISSING_PEERS" }

    // ── Monitor events ────────────────────────────────────────────
    case object MonitorOutcomeReady extends Event { val show = "MONITOR_OUTCOME_READY" }
    case object MonitorStateGone extends Event { val show = "MONITOR_STATE_GONE" }

    // ── Download/initialization events ────────────────────────────
    case object DownloadInitStart extends Event { val show = "DOWNLOAD_INIT_START" }
    case object DownloadInitFetch extends Event { val show = "DOWNLOAD_INIT_FETCH" }
    case object DownloadInitWaiting extends Event { val show = "DOWNLOAD_INIT_WAITING" }
    case object DownloadInitDeferred extends Event { val show = "DOWNLOAD_INIT_DEFERRED" }
    case object DownloadInitNoPeers extends Event { val show = "DOWNLOAD_INIT_NO_PEERS" }
    case object DownloadInitMismatch extends Event { val show = "DOWNLOAD_INIT_MISMATCH" }
    case object DownloadInitError extends Event { val show = "DOWNLOAD_INIT_ERROR" }
    case object DownloadInitRecoveryImmediate extends Event { val show = "DOWNLOAD_INIT_RECOVERY_IMMEDIATE" }
    case object DownloadInitRecoveryDeferred extends Event { val show = "DOWNLOAD_INIT_RECOVERY_DEFERRED" }
    case object DownloadInitReadyPromotion extends Event { val show = "DOWNLOAD_INIT_READY_PROMOTION" }
    case object InitDownloadFailureTracked extends Event { val show = "INIT_DOWNLOAD_FAILURE_TRACKED" }

    // ── Rollback events ───────────────────────────────────────────
    case object RollbackInitStart extends Event { val show = "ROLLBACK_INIT_START" }
    case object RollbackStateCleared extends Event { val show = "ROLLBACK_STATE_CLEARED" }
    case object RollbackBootstrapActive extends Event { val show = "ROLLBACK_BOOTSTRAP_ACTIVE" }
    case object RollbackFirstRoundDeferred extends Event { val show = "ROLLBACK_FIRST_ROUND_DEFERRED" }
    case object RollbackQuorumFeasible extends Event { val show = "ROLLBACK_QUORUM_FEASIBLE" }

    // ── Recovery events ───────────────────────────────────────────
    case object RecoveryStateTransition extends Event { val show = "RECOVERY_STATE_TRANSITION" }
    case object RecoveryTransitionFailed extends Event { val show = "RECOVERY_TRANSITION_FAILED" }
    case object ForcedRoundCompletionOnRecovery extends Event { val show = "FORCED_ROUND_COMPLETION_ON_RECOVERY" }
    case object RecoveryDownloadPhase extends Event { val show = "RECOVERY_DOWNLOAD_PHASE" }

    // ── Declaration-receive events (for cross-node timeline reconstruction) ────
    // Phase transitions are already captured by STATE_UPDATED (Category.Phase) with
    // status="X→Y" format — no separate event needed.
    case object DeclarationReceived extends Event { val show = "DECL_RECEIVED" }
    case object DeclarationWithdrawn extends Event { val show = "DECL_WITHDRAWN" }
    case object DeclarationAckReceived extends Event { val show = "DECL_ACK_RECEIVED" }

    // ── Force leave events ────────────────────────────────────────
    case object ForceLeaveFromInitFailures extends Event { val show = "FORCE_LEAVE_FROM_INIT_FAILURES" }
    case object ForceLeaveInitFailuresAlreadyLeaving extends Event { val show = "FORCE_LEAVE_INIT_FAILURES_ALREADY_LEAVING" }
    case object ForceLeaveInitFailuresSuccess extends Event { val show = "FORCE_LEAVE_INIT_FAILURES_SUCCESS" }
    case object ForceLeaveInitFailuresFailed extends Event { val show = "FORCE_LEAVE_INIT_FAILURES_FAILED" }
    case object ForceLeaveAlreadyLeaving extends Event { val show = "FORCE_LEAVE_ALREADY_LEAVING" }
    case object ForceLeaveSuccess extends Event { val show = "FORCE_LEAVE_SUCCESS" }
    case object ForceLeaveFailed extends Event { val show = "FORCE_LEAVE_FAILED" }
    case object ForceLeaveTriggered extends Event { val show = "FORCE_LEAVE_TRIGGERED" }
    case object RecoveryDownloadTriggered extends Event { val show = "RECOVERY_DOWNLOAD_TRIGGERED" }
    // Alpha.97 same-key soft reset: an in-place clear of round-volatile state
    // (artifacts, VCC, vote locks, withdrawals) while preserving the per-peer declaration
    // map, so a wedged round can re-evaluate without flipping NodeState out of Ready.
    // Fired when consecutive VCC-validation rejections (stale-local-view category) or
    // artifact-hash-mismatch failures (artifact-mismatch category) at the same key cross
    // their respective thresholds, AND useful peer declarations are present to rebuild
    // from, AND the per-key soft-reset budget is not exhausted.
    case object SoftResetTriggered extends Event { val show = "SOFT_RESET_TRIGGERED" }
    case object SoftResetSuppressed extends Event { val show = "SOFT_RESET_SUPPRESSED" }

    // ── MPT (Merkle Patricia Trie) events ─────────────────────────
    case object MptSavepointRestored extends Event { val show = "MPT_SAVEPOINT_RESTORED" }
    case object MptSavepointDiscardedWrongKey extends Event { val show = "MPT_SAVEPOINT_DISCARDED_WRONG_KEY" }
    case object MptRestoredAfterFailure extends Event { val show = "MPT_RESTORED_AFTER_FAILURE" }

    // ── Persistence events ────────────────────────────────────────
    case object PersistFailed extends Event { val show = "PERSIST_FAILED" }

    // ── Communication events ──────────────────────────────────────
    case object DirectPushFailed extends Event { val show = "DIRECT_PUSH_FAILED" }
  }

  // ── Formatting ──────────────────────────────────────────────────

  /** Build a structured log line with type-safe event.
    *
    * @param category
    *   Type-safe category (e.g. `Category.Lifecycle`, `Category.Phase`)
    * @param round
    *   The consensus round key (ordinal), or `"n/a"` when not yet known
    * @param role
    *   `"Leader"`, `"Validator"`, or `"n/a"`
    * @param event
    *   Type-safe event from the Event ADT
    * @param pairs
    *   Additional key=value pairs (trigger, hash, etc.)
    * @return
    *   Formatted string like `[CONSENSUS:LIFECYCLE] round=42 role=Leader event=ROUND_STARTED ...`
    */
  def format(category: Category, round: String, role: String, event: Event, pairs: (String, String)*): String = {
    val sb = new StringBuilder(128)
    sb.append("[CONSENSUS:").append(category.show).append("] round=").append(round).append(" role=").append(role)
    sb.append(" event=").append(event.show)
    pairs.foreach { case (k, v) => sb.append(' ').append(k).append('=').append(v) }
    sb.toString
  }

  // ── Type-safe convenience loggers ───────────────────────────────

  def info[F[_]: Applicative](
    logger: SelfAwareStructuredLogger[F],
    category: Category,
    round: String,
    role: String,
    event: Event,
    pairs: (String, String)*
  ): F[Unit] =
    logger.info(format(category, round, role, event, pairs: _*))

  def warn[F[_]: Applicative](
    logger: SelfAwareStructuredLogger[F],
    category: Category,
    round: String,
    role: String,
    event: Event,
    pairs: (String, String)*
  ): F[Unit] =
    logger.warn(format(category, round, role, event, pairs: _*))

  def debug[F[_]: Applicative](
    logger: SelfAwareStructuredLogger[F],
    category: Category,
    round: String,
    role: String,
    event: Event,
    pairs: (String, String)*
  ): F[Unit] =
    logger.debug(format(category, round, role, event, pairs: _*))

  def error[F[_]: Applicative](
    logger: SelfAwareStructuredLogger[F],
    category: Category,
    round: String,
    role: String,
    event: Event,
    pairs: (String, String)*
  ): F[Unit] =
    logger.error(format(category, round, role, event, pairs: _*))

  /** Log at error level with an attached throwable (type-safe). */
  def errorCause[F[_]: Applicative](
    logger: SelfAwareStructuredLogger[F],
    cause: Throwable,
    category: Category,
    round: String,
    role: String,
    event: Event,
    pairs: (String, String)*
  ): F[Unit] =
    logger.error(cause)(format(category, round, role, event, pairs: _*))

  /** Log at warn level with an attached throwable (type-safe). */
  def warnCause[F[_]: Applicative](
    logger: SelfAwareStructuredLogger[F],
    cause: Throwable,
    category: Category,
    round: String,
    role: String,
    event: Event,
    pairs: (String, String)*
  ): F[Unit] =
    logger.warn(cause)(format(category, round, role, event, pairs: _*))

  // ── Helpers ─────────────────────────────────────────────────────

  /** Determine the node's role based on whether it is the current leader. */
  def role(selfId: PeerId, leader: PeerId): String =
    if (selfId == leader) "Leader" else "Validator"

  /** Truncated peer ID for log display (first 8 hex chars). */
  def pid(p: PeerId): String = p.show.take(8)

  /** Format a list of peer IDs for log display, truncating to at most `max` entries. */
  def pids(peers: Iterable[PeerId], max: Int = 5): String = {
    val truncated = peers.take(max).map(pid).mkString(",")
    if (peers.size > max) s"$truncated...(+${peers.size - max})" else truncated
  }
}
