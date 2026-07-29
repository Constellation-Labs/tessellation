package io.constellationnetwork.node.shared.config

import cats.data.NonEmptySet
import cats.syntax.option._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.domain.statechannel.FeeCalculatorConfig
import io.constellationnetwork.node.shared.infrastructure.selfhealth.SelfHealthHint
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node.{NodeState, RewardFraction}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.transaction.TransactionAmount
import io.constellationnetwork.schema.{NonNegFraction, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash

import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.types.numeric._
import fs2.io.file.Path

object types {

  // Keep this parameter order aligned with the explicit forProduct reader in ext.pureconfig.
  // Fields share similar map types, so reordering them without updating that reader can miswire gates.
  case class FieldsAddedOrdinals(
    tessellation3Migration: Map[AppEnvironment, SnapshotOrdinal],
    tessellation301Migration: Map[AppEnvironment, SnapshotOrdinal],
    checkSyncGlobalSnapshotField: Map[AppEnvironment, SnapshotOrdinal],
    metagraphSyncData: Map[AppEnvironment, SnapshotOrdinal],
    updatedLastSyncGlobalOrder: Map[AppEnvironment, SnapshotOrdinal],
    updatedLastSyncGlobalFromPeersInConsensus: Map[AppEnvironment, SnapshotOrdinal],
    updatingCombineFunctionSpendActions: Map[AppEnvironment, SnapshotOrdinal],
    fixingAllowSpendExpiration: Map[AppEnvironment, SnapshotOrdinal],
    fixingAllowSpendAndTokenLockValidation: Map[AppEnvironment, SnapshotOrdinal],
    setSumFix: Map[AppEnvironment, SnapshotOrdinal],
    // Ordinal-gated balance source for state-channel fee affordability (commit dd6e83a19). At/after this ordinal the fee
    // check reads the metagraph owner's balance from the deterministic accept() context (lastGlobalSnapshotInfo.balances);
    // below it from the pre-fix mptStore.getBalance path, so already-signed history re-derives byte-identically. Per-env
    // activation ordinals live in the `fields-added-ordinals` HOCON (see docs/operations/fields-added-ordinals.md): testnet
    // activates at its v4.0.0->alpha.0 cutover ordinal, dev at 0 (genesis-fresh), mainnet/integrationnet are placeholders.
    scFeeBalanceFromContext: Map[AppEnvironment, SnapshotOrdinal] = Map.empty,
    // Ordinal-gated per-field MPT sub-trie roots in GlobalSnapshotStateProof. This changes signed proof bytes, so it must
    // stay fail-closed until each public network deliberately activates it at a coordinated cold-restart ordinal.
    subTrieRoots: Map[AppEnvironment, SnapshotOrdinal] = Map.empty,
    // At/after this ordinal delegated validator rewards use the full frozen signing committee. Below it, replay the
    // short-lived evidence-score filter exactly as deployed, so already-signed reward transactions remain reproducible.
    delegatedRewardsFullCommittee: Map[AppEnvironment, SnapshotOrdinal] = Map.empty,
    // At/after this global ordinal, fee transactions require cryptographic authorization by their source wallet.
    feeTransactionSecurity: Map[AppEnvironment, SnapshotOrdinal] = Map.empty,
    // Ordinal-gated GSI dust sweeps (state deflation), per environment, keyed by the ordinal each sweep fires at. Loaded from
    // the `fields-added-ordinals.dust-sweeps` HOCON block, so the jar hash plus the environment is the determinism fence (the
    // conf is packaged into the assembly jar and peers only connect to matching jar hashes). Default empty: an environment with
    // no entry never sweeps. See `DustSweep` and `GlobalSnapshotDustSweep`.
    dustSweeps: Map[AppEnvironment, SortedMap[SnapshotOrdinal, DustSweep]] = Map.empty
  ) {
    def feeTransactionSecurityFor(environment: AppEnvironment): SnapshotOrdinal =
      feeTransactionSecurity.getOrElse(environment, SnapshotOrdinal.MaxValue)
  }

  /** A single ordinal-gated GSI dust sweep (state deflation).
    *
    * @param threshold
    *   sweep balances whose value is `<=` this threshold (subject to the other safety gates)
    * @param collectionAddress
    *   `None` = burn (reported total supply drops by the swept sum); `Some(addr)` = credit the collected sum to `addr` (total supply
    *   preserved)
    */
  final case class DustSweep(
    threshold: Balance,
    collectionAddress: Option[Address]
  )

  case class MetagraphsSyncConfig(
    maxUnappliedGlobalChangeOrdinals: PosInt
  )

  /** Configuration for `LocalHealthMonitor` (Phase A of the self-health throttle, see docs/consensus/self-health-throttle.md).
    *
    * Thresholds match the alpha.73 overnight metrics-deep-dive:
    *   - GC pause > 5s in last 5 min is degraded; > 30s is critical (8804651b's 81s is unambiguous critical).
    *   - Load1m / vCPU > 3.0 is degraded; > 6.0 is critical (90eb1ed3's 54/8 = 6.75 is critical, 9561959b's 39/8 = 4.88 is degraded).
    *
    * `operatorOverride` lets an operator pin a peer's self-report without restarting: setting it to `Critical` deprioritizes the peer in
    * the next round's leader selection. Useful as a stop-gap for the 6 hardware-marginal community peers while the auto-detection
    * thresholds bake.
    *
    * Phase B inclusion in `deterministicConfigHash`: the THRESHOLDS feed each peer's locally-computed hint; the hint then enters
    * consensus-agreed state via Facility. If thresholds diverge, two peers can compute different hints from the same signals -> different
    * tiers in `selectLeaderWeighted` -> fork. So thresholds must be in the hash. `operatorOverride` is per-peer (different by design) and
    * stays out.
    */
  case class LocalHealthMonitorConfig(
    pollInterval: FiniteDuration = 10.seconds,
    historyWindow: FiniteDuration = 5.minutes,
    gcPauseDegradedMs: Long = 5000L,
    gcPauseCriticalMs: Long = 30000L,
    loadPerVcpuDegraded: Double = 3.0,
    loadPerVcpuCritical: Double = 6.0,
    operatorOverride: Option[SelfHealthHint] = None
  )

  object LocalHealthMonitorConfig {
    val default: LocalHealthMonitorConfig = LocalHealthMonitorConfig()
  }

  case class SharedConfigReader(
    gossip: GossipConfig,
    leavingDelay: FiniteDuration,
    stateAfterJoining: NodeState,
    collateral: Option[CollateralConfig],
    trust: SharedTrustConfig,
    snapshot: SharedSnapshotConfig,
    feeConfigs: Map[AppEnvironment, Map[SnapshotOrdinal, FeeCalculatorConfig]],
    priorityPeerIds: Map[AppEnvironment, NonEmptySet[PeerId]],
    lastKryoHashOrdinal: Map[AppEnvironment, SnapshotOrdinal],
    lastLegacyStateProofOrdinal: Map[AppEnvironment, SnapshotOrdinal],
    incrementalDelegatedStakingStartingOrdinal: Map[AppEnvironment, SnapshotOrdinal],
    addresses: AddressesConfig,
    allowSpends: AllowSpendsConfig,
    tokenLocks: TokenLocksConfig,
    lastGlobalSnapshotsSync: LastGlobalSnapshotsSyncConfig,
    validationErrorStorage: ValidationErrorStorageConfig,
    delegatedStaking: DelegatedStakingConfig,
    fieldsAddedOrdinals: FieldsAddedOrdinals,
    metagraphsSync: MetagraphsSyncConfig,
    priceOracle: Map[AppEnvironment, PriceOracleConfig],
    snapshotBinarySenderTimeouts: SnapshotBinarySenderTimeoutsConfig,
    clickHouseConfig: ClickHouseAppConfig,
    snapshotServing: Option[SnapshotServingConfig] = None
  )

  case class SharedConfig(
    environment: AppEnvironment,
    gossip: GossipConfig,
    http: HttpConfig,
    leavingDelay: FiniteDuration,
    stateAfterJoining: NodeState,
    collateral: CollateralConfig,
    trustStorage: TrustStorageConfig,
    priorityPeerIds: Option[NonEmptySet[PeerId]],
    snapshotSize: SnapshotSizeConfig,
    feeConfigs: SortedMap[SnapshotOrdinal, FeeCalculatorConfig],
    lastKryoHashOrdinal: Map[AppEnvironment, SnapshotOrdinal],
    lastLegacyStateProofOrdinal: Map[AppEnvironment, SnapshotOrdinal],
    incrementalDelegatedStakingStartingOrdinal: Map[AppEnvironment, SnapshotOrdinal],
    addresses: AddressesConfig,
    allowSpends: AllowSpendsConfig,
    tokenLocks: TokenLocksConfig,
    lastGlobalSnapshotsSync: LastGlobalSnapshotsSyncConfig,
    validationErrorStorage: ValidationErrorStorageConfig,
    delegatedStaking: DelegatedStakingConfig,
    fieldsAddedOrdinals: FieldsAddedOrdinals,
    metagraphsSync: MetagraphsSyncConfig,
    priceOracle: PriceOracleConfig,
    snapshotBinarySenderTimeouts: SnapshotBinarySenderTimeoutsConfig,
    snapshotTimeoutsConfig: SnapshotTimeoutsConfig,
    clickHouseConfig: ClickHouseAppConfig,
    mptSnapshotInfoPath: Path,
    snapshotServingConfig: Option[SnapshotServingConfig] = None,
    localHealthMonitor: LocalHealthMonitorConfig = LocalHealthMonitorConfig.default
  )

  case class SharedTrustConfig(
    storage: TrustStorageConfig
  )

  case class SharedSnapshotConfig(
    size: SnapshotSizeConfig,
    timeouts: SnapshotTimeoutsConfig,
    mptSnapshotInfoPath: Path
  )

  case class SnapshotSizeConfig(
    singleSignatureSizeInBytes: PosLong,
    maxStateChannelSnapshotBinarySizeInBytes: PosLong
  )

  case class RumorStorageConfig(
    peerRumorsCapacity: PosLong,
    activeCommonRumorsCapacity: NonNegLong,
    seenCommonRumorsCapacity: NonNegLong
  )

  case class GossipDaemonConfig(
    peerRound: GossipRoundConfig,
    commonRound: GossipRoundConfig
  )

  case class GossipRoundConfig(
    fanout: PosInt,
    interval: FiniteDuration,
    maxConcurrentRounds: PosInt,
    maxOrdinalsPerRequest: Option[PosInt] = None,
    // Per-peer cooldown for chronic gossip failures. After `failureCountThreshold`
    // failed gossip rounds within `failureWindow`, the peer is excluded from this
    // runner's peer selection until enough failure timestamps age out of the window.
    // This bypasses the `/session`-based LocalHealthcheck recovery loop that keeps
    // chronic non-signers (peers whose `/session` works but who don't participate
    // in consensus rumor exchange) cycling back into the gossip pool every cycle.
    // Per-runner state: peer-round and common-round track failures independently.
    failureCountThreshold: PosInt = PosInt.unsafeFrom(3),
    failureWindow: FiniteDuration = 60.seconds
  )

  case class GossipTimeoutsConfig(
    routes: FiniteDuration,
    client: FiniteDuration
  )

  case class GossipConfig(
    storage: RumorStorageConfig,
    daemon: GossipDaemonConfig,
    timeouts: GossipTimeoutsConfig
  )

  case class ConsensusConfig(
    timeTriggerInterval: FiniteDuration,
    declarationTimeout: FiniteDuration,
    declarationRangeLimit: NonNegLong,
    lockDuration: FiniteDuration,
    eventCutter: EventCutterConfig,
    maxFacilitatorCount: Option[PosInt] = None,
    reStallTimeout: Option[FiniteDuration] = None,
    noProgressTimeout: Option[FiniteDuration] = None,
    maxStallCycles: Int = 3,
    maxRoundDuration: Option[FiniteDuration] = None,
    removalPenaltyRounds: Int = 3,
    candidateDeferralRounds: Int = 3,
    // B2 re-admission probation: when a removed peer's `removalPenalty` expires, they enter
    // `readmissionCountdown` at this value (instead of returning directly to the committee),
    // and are excluded from the round until an AdmissionCertificate for them is embedded in
    // a finished proposal. Symmetric to `candidateDeferralRounds` (first-time joiner probation),
    // but sourced from a different lifecycle event (penalty expiry vs. fresh eligibility).
    readmissionProbationRounds: Int = 3,
    facilitiesTimeoutMultiplier: Double = 0.75,
    proposalsTimeoutMultiplier: Double = 1.5,
    signaturesTimeoutMultiplier: Double = 0.75,
    // Grace delay after reaching the majority-signatures quorum, before finalizing
    // the round. Catches late-arriving signatures from all facilitators so the final
    // `signedArtifact.proofs` set matches the full committee rather than dropping
    // the last 1-2 peers that crossed quorum by milliseconds. Complements the local
    // self-store of the signer's own signature (which closes the self-race
    // deterministically). Timing-only: NOT included in deterministicConfigHash
    // because nodes with different grace periods still produce the same downstream
    // snapshotHash (the artifact hash, not the signed-artifact hash, is used
    // canonically — see buildFinishedTransition). Tune down if round throughput
    // becomes a bottleneck; tune up if network jitter routinely drops late signers.
    signatureGracePeriod: FiniteDuration = 3.seconds,
    // SHORT grace delay used when the CORE committee has fully signed but not the whole
    // committee, to collect late-arriving Tier 1 signatures before finalizing. Distinct from
    // `signatureGracePeriod` (which covers the Core-incomplete case): once every Core member has
    // signed, liveness no longer needs the full window, but we still want Tier 1 signatures to
    // land in `signedArtifact.proofs` and participation evidence (the alpha.153 regression where
    // finalizing the INSTANT Core completed dropped every Tier 1 proof). Delegated rewards follow
    // frozen committee membership, not proofs. The bounded wait trades a few ms of finalization
    // latency for evidence completeness. Timing-only:
    // NOT included in `deterministicConfigHash`, for the SAME reason as `signatureGracePeriod` --
    // the canonical `snapshotHash` is the agreed ARTIFACT hash, not the signed-artifact hash, so
    // two nodes with different grace periods still produce the same downstream snapshotHash; this
    // lever only changes which proofs ride along, never a consensus-decided value. Default
    // `signatureGracePeriod / 4` (a short fraction of the Core-incomplete window).
    tier1SignatureGracePeriod: FiniteDuration = 750.milliseconds,
    // Delay between assembling/receiving a certified view-change and locally applying it
    // to reset the round into the next view. The VCC is still stored and gossiped immediately;
    // this only gives ordinary proposal/signature traffic time to arrive before a timeout
    // certificate rewinds useful local progress. Timing-only: not included in
    // deterministicConfigHash because view advancement still requires the same signed VCC.
    // Operators should keep values in the same practical band across a network; wildly
    // divergent values are safe but can create noisy early-applier/late-applier behavior.
    viewChangeApplyDelay: FiniteDuration = 7.seconds,
    maxConsecutiveAbandonments: Int = 5,
    // Defensive threshold for forcing a VCV emission when the cluster has
    // already abandoned the same ordinal this many times. Bypasses the "missing-still-responsive"
    // gate in StallDetector which is correct for transient gossip jitter on round 1 but wrong
    // when applied across N abandons of the same key. With each abandon ~57s, default 3 fires
    // after ~3 minutes of cluster being stuck at the same (ord, view=0). Setting equal to
    // maxConsecutiveAbandonments would force VCV at the same moment recovery would normally
    // be triggered (and is currently suppressed under peersAtHigherKey=0); setting strictly
    // less than maxConsecutiveAbandonments lets the new view advance before the recovery gate
    // would normally fire. Consensus-critical: included in deterministicConfigHash so all
    // operators force VCV at the same count, ensuring all honest nodes converge on the same
    // (fromView, toView) for VCC assembly within bounded skew.
    forceViewChangeAbandonments: Int = 3,
    // v19 phase 2 view-from-time anchor: divisor for deriving a pacemaker timeout hint from
    // wall-clock progress since the parent snapshot's `consensusEndTime`. Pairs with the
    // producer side (`Facility.proposerClockMs` + `ConsensusEndTime` median + `recentRoundEndTimes`
    // window). At round start, each peer computes
    //   timeView = (local_now - parent.consensusEndTime) / viewInterval
    // and the stall detector may emit a signed VCV when local view lags this hint. The hint must
    // not directly seed proposal-critical `viewNumber`; view advancement requires VCV quorum/VCC.
    // NTP skew across honest nodes is +/- 10ms on AWS-class infra; with a 60s default the boundary
    // resolution is ~1.7 parts in 10,000 -- well below view-transition granularity. Consensus-critical:
    // included in `deterministicConfigHash` so honest peers with the same parent + same `now`
    // compute the same timeView. See `docs/consensus/view-from-time-anchor.md`.
    //
    // Raised 30s -> 60s: testnet log analysis (2026-05-28) showed median round duration ~45s, which
    // EXCEEDS the old 30s interval, so `floor((now - parentEndTime) / viewInterval)` evaluated to >= 1
    // in the proposal phase on EVERY round -- firing one timestamp-pacemaker VCV per round ~300ms
    // before signatures even began. The round still finalized at view 0, so the VCV was wasted work
    // AND it masked genuine stalls (every round carried a forced view change, so a real stall was
    // indistinguishable in metrics/logs). At 60s, `floor(45/60)=0`, the per-round VCV disappears, and
    // a genuine ~120s stall still trips `timeView >= 2` -> view advance. The cost is that a real stall
    // now takes ~60s instead of ~30s to trip time-derived view advance; the abandon / StallDetector
    // paths remain a faster backstop. Watch round duration: if it creeps toward 60s under load, move
    // to an adaptive interval (codex pacemaker item #4) rather than another fixed bump.
    viewInterval: FiniteDuration = 60.seconds,
    // Signer-history window parameters.
    //
    // `tighteningWindow` is LIVE: it is the size (in ordinals) of the rolling `recentSigners`
    // window the StateAdvancers maintain. As of v22 that window feeds the tier-demotion
    // hysteresis (`TierTransitions` reads the most-recent `DemotionConsecutiveMisses` entries of
    // it) and is pinned to the same horizon as `recentProofSizes`.
    //
    // `minParticipationInWindow` is INERT (dead config). It parameterized the original v19
    // active-set tightening FILTER -- "narrow the round N+1 committee to peers who signed M of
    // the last K outcomes" -- which was RETIRED when the multi-committee tier partition
    // (`TierTransitions` + `CommitteeBuilder`) replaced it. No code reads it today; it survives
    // only in this field, the `deterministicConfigHash` string, and the conf files. Kept (not
    // removed) so the schema/hash is unchanged; a hard removal is deferred to a future schema
    // cleanup. The v22 demotion hysteresis does NOT use it -- it uses the compiled-in
    // `TierTransitions.DemotionConsecutiveMisses` constant instead.
    //
    // `activeFacilitatorFloor` is the emergency bypass threshold for active admission and is also
    // read by the rollback/ready-participation gates. Admission score gating remains enabled at
    // and above this floor; below it the full selected pool is admitted for bootstrap/collapse
    // recovery. All three values are consensus-critical (in `deterministicConfigHash`) so
    // divergent operator values are rejected at handshake.
    tighteningWindow: Int = 10,
    minParticipationInWindow: Int = 6,
    activeFacilitatorFloor: Int = 4,
    // Deterministic active-facilitator expansion. Recent signers are preferred, but when
    // the recent-signer pool is below this target the StateCreator admits additional selected
    // facilitators ranked by consensus-agreed peerQuality and stable peer id. This is a target,
    // not a cap: if more recent signers exist, they remain active up to activeFacilitatorMax.
    // Consensus-critical because it changes the active facilitator set.
    activeFacilitatorTarget: Option[Int] = None,
    // Hard cap for deterministic active-facilitator admission. None preserves the legacy
    // maxFacilitatorCount-selected pool size; testnet sets this lower to avoid re-opening the
    // full selected denominator while still letting healthy community peers prove themselves.
    activeFacilitatorMax: Option[Int] = None,
    // v27 consensus peer controller: bounded integral score used for active role
    // admission. All fields below are consensus-critical because they change which
    // peers can enter the rewards-affecting active set from the same parent state.
    activeAdmissionPromoteThreshold: Int = 100,
    activeAdmissionRetainThreshold: Int = 70,
    activeAdmissionDemoteThreshold: Int = 40,
    activeAdmissionMaxScore: Int = 150,
    activeAdmissionSignatureReward: Int = 20,
    activeAdmissionResponderReward: Int = 5,
    activeAdmissionMissedActivePenalty: Int = 15,
    activeAdmissionTimeoutMissingPenalty: Int = 10,
    activeAdmissionEvictedPenalty: Int = 40,
    activeAdmissionDegradedPenalty: Int = 5,
    activeAdmissionCriticalPenalty: Int = 20,
    activeAdmissionPassiveDecay: Int = 1,
    activeAdmissionMaxExpansionPerRound: Int = 1,
    activeAdmissionExpansionIntervalRounds: Int = 1,
    // Bounded probation re-entry lane: minimum number of probation (below-promote-threshold,
    // "rehabilitating") peers admitted to the active set per round EVEN WHEN the per-round
    // expansion budget (`activeAdmissionMaxExpansionPerRound`) is exhausted. A probation peer that
    // signs the latest round keeps competing ahead of fresh candidates for one of these bounded
    // seats until it reaches the retain band; missing the latest round ends the lease. This avoids
    // one-round admission churn and lets responsive peers accumulate enough signed evidence to
    // graduate. Capped by the active-set max. Probation peers are non-quorum-bearing (routed to
    // `nonCorePeers` in `CommitteeBuilder`), so widening the lane cannot affect quorum feasibility.
    // Env-resolved at the consensus construction site from
    // `SnapshotConfig.activeAdmissionMinProbationReentrySlots.get(env)` (the coreCommitteeSize
    // pattern); default 0 leaves the lane inert. Consensus-critical: it changes the active committee
    // -> roundStartFacilitators -> facilitatorsHash, so it is folded into `deterministicConfigHash`
    // and divergent operator values handshake-reject.
    activeAdmissionMinProbationReentrySlots: Int = 0,
    // Recent-signer pool lookback depth (in ordinals): how far back a peer may have last signed and
    // still hold a sticky, score-gated active seat instead of churning through the volatile
    // expansion/reserve fill. Decoupled from the demotion hysteresis (which stays at
    // `TierTransitions.DemotionConsecutiveMisses` = 3 and independently keeps non-recent signers out
    // of Core), so widening this only changes active-set eligibility, never the quorum-bearing Core.
    // Env-resolved at the consensus construction site from
    // `SnapshotConfig.activeAdmissionRecentSignerWindow.get(env)` (the coreCommitteeSize pattern),
    // floored to DemotionConsecutiveMisses. Default 3 preserves the pre-change lookback; testnet
    // widens to the full persisted `recentSigners` window (`tighteningWindow`). Consensus-critical:
    // it changes the active committee -> roundStartFacilitators -> facilitatorsHash, so it is folded
    // into `deterministicConfigHash` and divergent operator values handshake-reject.
    activeAdmissionRecentSignerWindow: Int = 3,
    // Controller evidence stage 3: duration (in ordinals) of a cert-anchored penalty. An
    // EvictionCertificate applied at ordinal N writes `penaltyUntil(target) = N + D`; an
    // AdmissionCertificate clears the entry. Pure ordinal comparisons, no per-round countdown.
    // Stage 4 (v32) activates the read side: `penaltyUntil` joins the eligibility filtering
    // in both StateCreators, so divergent operator values would change committee derivation.
    // Consensus-critical: included in `deterministicConfigHash` since v32 so divergent values
    // are rejected at the Facility handshake instead of silently forking.
    penaltyDurationOrdinals: Int = 100,
    monitorSummaryInterval: FiniteDuration = FiniteDuration(10, "s"),
    peerScoreLogInterval: FiniteDuration = FiniteDuration(60, "s"),
    qualityDecayThreshold: Int = 100,
    eventTriggerThreshold: Int = 1,
    eventTriggerCooldown: FiniteDuration = FiniteDuration(5, "s"),
    eventGossipHeartbeatInterval: FiniteDuration = FiniteDuration(10, "s"),
    eventGossipPullInterval: FiniteDuration = FiniteDuration(20, "s"),
    forkLagThreshold: Long = 10,
    quorumThresholdFraction: Double = 1.0,
    exponentialPenaltyBase: Int = 2,
    maxRemovalPenaltyRounds: Int = 10000,
    // Chronic non-signer filter: exclude peers from the committee if their
    // historical participation rate is below `minParticipationRatio` AFTER they have
    // been observed for at least `minParticipationObservations` rounds. Uses the
    // consensus-agreed `peerQuality` outcome field, so all nodes compute the same
    // exclusion set deterministically. Applied in addition to removal penalties and
    // deferral, as a hard filter that keeps chronic flaky community peers out of the
    // committee before round start (preventing mid-round eviction cascades).
    minParticipationObservations: Int = 5,
    // Default reverted 0.7 -> 0.5. The combined prior tightenings
    // shrank the eligible facilitator set to the point where even source nodes (e.g.
    // e2f4496e:21/31 = 0.677) crossed below the gate and were excluded by their own
    // classifier. alpha.52's 0.5 value ran the cluster stably for 5 days. The wider
    // witness pool and peersAtHigherKey gate provide the safety / liveness
    // reinforcements the tightenings were targeting, without compressing the eligible set.
    minParticipationRatio: Double = 0.5,
    // Leader-rotation band threshold. Integer percent (0..100) such that a graduated peer with
    // `completed * 100 >= participated * threshold` lands in the leader-eligible tier; the rest
    // fall into a fallback tier used only when no eligible peer is available. Default 50 mirrors
    // `minParticipationRatio`. Consensus-critical: included in `deterministicConfigHash`.
    // Deploy history: introduced in v14 (consensusSchemaVersion); see the v14 entry below for the
    // ratchet failure it replaces.
    leaderRotationMinRatioPct: Int = 50,
    // Hard leader-eligible floor on the integer quality score
    // `(completed/participated) * (1 - viewChangesCaused/participated)`. Inside
    // `selectLeaderWeighted`, peers below the floor are EXCLUDED from the leader-eligible pool
    // entirely (not just demoted to tier 1). They remain facilitators (vote / sign / witness),
    // just cannot be selected as leader. Closes the chronic-leader-loop wedge:
    // peers with quality score decaying through 0.07-0.25 (driven by view-change
    // rate, not completion rate) were still tier 1 and `sorted[viewNumber % size]` continued
    // to walk through them on view rotation, dragging out 30-90 minute wedges per chronic-
    // leader episode. The raw completion ratio missed this case because the looping leaders
    // STILL completed rounds (as followers after view advance), only failed AS leaders.
    //
    // Default 20 is intentionally well below `leaderRotationMinRatioPct` (50). The two
    // thresholds work in concert: 50 deprioritises within the leader pool (tier 1 fallback
    // band); 20 removes from the pool entirely. Setting them equal would collapse the
    // gradient and reintroduce the cliff that motivated the tier-1 band.
    //
    // The score is consensus-agreed because all three inputs (peerQuality.completed,
    // peerQuality.participated, peerViewChanges) are populated by the StateAdvancer from
    // deterministic functions of the prior outcome at round finalization.
    //
    // Pool collapse is guarded by `minLeaderPoolSize`: if fewer than that many peers clear the
    // 0.20 floor, the selector falls back to the full graduated set to avoid starvation
    // (bootstrap, mass-chronic, single-node test rigs).
    //
    // Consensus-critical: included in `deterministicConfigHash`. Different operator values
    // would compute different leader pools, different leaders, and silently fork.
    hardLeaderQualityScorePct: Int = 20,
    // Minimum size of the hard-filtered leader pool. If `selectLeaderWeighted`
    // filters fewer than this many leader-eligible peers (after applying
    // `hardLeaderQualityScorePct`), it falls back to the full graduated facilitator set so
    // consensus does not starve. Default 2 matches the call-site graduation ladder
    // (`graduatedLeaderPool.size >= 2`): a single-peer pool deadlocks view rotation because
    // `viewNumber % 1 == 0` always selects the same peer, so we require at least two healthy
    // candidates before excluding the chronic ones; below that, the fallback re-admits the
    // graduated set so rotation can still cover the round.
    //
    // Consensus-critical: included in `deterministicConfigHash`.
    minLeaderPoolSize: Int = 2,
    // Minimum-history floor for chronic classification. Recommended as a
    // separate knob: the existing `minParticipationObservations` is reused as the leader-
    // graduation gate (state-creator:470), so bumping it to 30 would also delay leader
    // eligibility — a different policy decision. Keeping the two knobs split lets us require
    // a long observation window before chronic classification (where false positives wedge
    // the cluster) without restricting which peers can lead.
    //
    // Effect: a peer needs `>= minObservationHistoryFloor` rounds of evidence before the
    // chronic filter can fire. At ~25-30s/round on testnet, 30 observations = ~12-15 minutes
    // of cluster activity per peer. Truly chronic peers (40% participation) reach the floor
    // after ~75 rounds (~30 minutes) — still classified, just with more evidence.
    //
    // Included in `deterministicConfigHash` because it changes the agreed-upon
    // chronicNonSigners set and therefore the committee composition.
    minObservationHistoryFloor: Int = 30,
    // Periodic reinstatement: every N ordinals, one chronic non-signer is rotated back into
    // the eligible committee pool for a single round to test whether they have recovered.
    // Necessary because once a peer is classified chronic they are excluded from the committee,
    // so their peerQuality.participated count stops growing — without reinstatement they
    // would stay chronic forever. Deterministic: all honest nodes compute the same
    // reinstatement round from the consensus-agreed key value and sort chronic set.
    // 0 disables reinstatement (peers stay chronic until manual intervention).
    chronicReinstatementInterval: Int = 100,
    // Phase 2 cold-restart protocol version flag. Included in `deterministicConfigHash` so pre-Phase-2 peers are
    // excluded from facilitator selection via the config-hash check. Set to 2 to enable the quorum-certified
    // view-change + local vote-lock protocol.
    lockOnVoteProtocolVersion: Int = 2,
    // Bootstrap warmup threshold: minimum proofs.size in any recent snapshot required to classify the chain as
    // post-bootstrap. While bootstrap is active (no recent snapshot meets this threshold), penalty accrual is
    // suppressed to avoid ejecting slow peers during the solo->multi transition. Consensus-critical because it
    // determines `penalizedThisRound` in the outcome; included in `deterministicConfigHash`.
    //
    // OPERATOR NOTE: this default (3) assumes the cluster will eventually reach a committee size >= 3.
    // For small deployments (single-node test rigs, 2-validator metagraphs), no recent snapshot will
    // ever meet the threshold and `isBootstrapActive` stays `true` for the cluster's lifetime —
    // silently disabling quality scoring (`penalizedThisRound` stays empty), exponential penalties,
    // and readmission machinery. Operators of small clusters should override this to match their
    // expected steady-state committee size (e.g. 2 for a 2-validator metagraph). Note that changing
    // this value bumps `deterministicConfigHash`, so all peers in the cluster must agree.
    bootstrapCompleteProofsThreshold: Int = 3,
    // Adaptive declaration timeout: during bootstrap (recentProofSizes shows no round >= bootstrapCompleteProofsThreshold),
    // the `declarationTimeout` is multiplied by this factor so fresh-start peers have more time to respond before
    // StallDetector fires view change / eviction. Post-bootstrap, the multiplier is 1.0 (tighter liveness).
    // Consensus-critical because it affects stall-cycle cadence, which in turn affects view-change emission and
    // abandonment timing — divergent values here would cause nodes to make view transitions at different moments.
    bootstrapDeclarationTimeoutMultiplier: Double = 2.0,
    // Local-only LIVENESS/TIMING knob (NOT a safety knob) for B2 admission voting. A probation
    // peer must be observed at the committed tip on this many consecutive StallDetector monitor
    // ticks before this node will emit an AdmissionVote. Prevents premature re-admission of
    // peers whose recovery download transiently presents the committed tip but that are not yet
    // stably participating.
    //
    // NOT in `deterministicConfigHash`: two honest nodes may have different streak counts for
    // the same peer due to local tick timing. Cert assembly still requires quorum-agreed signed
    // votes, so streak drift only shifts WHEN a given node emits its vote, not the eventual
    // cert-assembly outcome. Mixed values across operators therefore change the timing of when
    // an AdmissionCertificate becomes available for embedding in proposals — a liveness
    // consequence, not a safety one.
    //
    // Values <= 0 are clamped to 1 at the read-site in StallDetector. Default 2 means two
    // consecutive monitor ticks (~500ms-1s of sustained at-tip correctness at typical polling
    // intervals).
    b2AdmissionAtTipStreak: Int = 2,
    // Local-only: number of rounds after a successful `initFromDownload` during which this node
    // refuses to lead, immediately self-deferring into a view change if elected.
    // A peer that just finished recovery has a freshly initialized consensus storage
    // and gossip mesh; if it's elected leader of the next round, it can't propose in time and
    // wedges the round for the full proposal-phase timeout (98s observed in E2E).
    // Self-deferring into view change converts that 98s wedge into a ~5s rotation.
    //
    // NOT in `deterministicConfigHash`: deferral is a self-defense decision, not a consensus
    // rule. Other peers still elect this node deterministically; this node refuses and emits a
    // ViewChangeVote, after which the standard quorum-certified VCC mechanism takes over.
    //
    // Default 3: at ~22s/round that's ~66s of cooldown, enough to fully prime fresh consensus
    // state without materially eating recovery budget. K=1 is too optimistic; K=10 is too long.
    recoveryLeaderCooldownRounds: Int = 3,
    // Alpha.97 same-key soft-reset budget. Caps the number of times the in-place soft
    // reset can fire at the same key before the caller (layer advancer's logVccReject or
    // artifact-mismatch path) falls through to the existing heavy `triggerRecoveryDownload`.
    //
    // The soft reset clears volatile round state (artifacts, VCC, vote locks, withdrawals)
    // while PRESERVING the per-peer declaration map, so the round re-evaluates from
    // observed peer declarations without taking this node out of Ready. Without this
    // budget the same wedge could trigger a silent reset loop; with it, three attempts
    // means the local view is unrecoverable in place and the heavy Download is justified.
    //
    // NOT in `deterministicConfigHash`: local self-defense, not consensus rule.
    //
    // Default 3 aligns with `maxConsecutiveValidationFailures` (the existing pre-Download
    // threshold on the artifact-hash mismatch path).
    maxSoftResetsAtSameKey: Int = 3,
    // Alpha.97 stale-local-view detection threshold. Number of consecutive VCC-validation
    // rejections (`view{N}_proposal_missing_vcc` outside the stale-slot pattern, or
    // `vcc_view_mismatch`) at the same key before the layer advancer attempts a soft
    // reset. Distinct from `maxConsecutiveValidationFailures` (which gates the artifact-
    // hash mismatch path).
    //
    // NOT in `deterministicConfigHash`: same rationale.
    //
    // Default 3: at ~22s/round, fires ~66s after the wedge starts.
    maxStaleLocalViewRejections: Int = 3,
    // Local-only LIVENESS knob: time the same divergent majority hash must persist before
    // `recoverIfForking` flips this node from Ready/Observing/WaitingForReady -> WaitingForDownload.
    // A first observation only RECORDS the divergence; recovery is triggered on a subsequent
    // observation if (now - firstSeenAt) >= forkConfirmationWindow.
    //
    // Why this exists: alpha.40 testnet, all three internal nodes detected the same
    // facilitators-hash divergence within 25s and ALL three flipped to WaitingForDownload
    // simultaneously. None remained as a metadata source for the others, so every recovery
    // download returned 503 -- a circular deadlock that wedged the cluster for 7+ minutes until
    // operator intervention. With a confirmation window, the first node to observe pauses long
    // enough that EITHER (a) the divergence resolves on its own (transient committee asymmetry
    // around eviction/admission cert application, the most likely root cause), OR (b) one node
    // confirms first and starts recovery while peers stay Ready to serve metadata.
    //
    // NOT in `deterministicConfigHash`: this is a per-node WHEN-to-recover decision, not a WHAT
    // is the cluster's view decision. Cluster-wide safety is unaffected; a divergent peer still
    // recovers, just after a gating delay.
    //
    // Default 30s: long enough that transient hash asymmetries (typically resolved within one
    // round-finalize, ~22-30s) don't trigger recovery, short enough that real forks are picked
    // up promptly. 0s disables the gate (legacy single-sample behaviour).
    forkConfirmationWindow: FiniteDuration = 30.seconds,
    // Local-only LIVENESS knob: minimum number of peer observations required before
    // `recoverIfForking` will treat a majority sample as authoritative. Below this threshold the
    // function logs and waits without updating its tracker. Prevents a sample of size 1
    // (totalObservations=1 in older log entries) from triggering recovery.
    //
    // NOT in `deterministicConfigHash`: same reason as forkConfirmationWindow.
    //
    // Default 2: requires at least one peer plus self before considering majority. For
    // single-peer / genesis topologies the value should be set to 1 via env-specific config.
    forkConfirmationMinObservations: Int = 2,
    // Schema-version anchor. Bumped on any consensus-protocol-level change that requires
    // a cluster-wide cold restart. Included in `deterministicConfigHash` so old-version
    // nodes (any node whose config omits this field defaults to a different value) compute
    // a different hash and cannot form a cluster with new-version nodes. Future schema bumps
    // just increment this field.
    //
    // History:
    //   v7: wire-format additions (observedResponders on Proposal +
    //     CollectingProposals; qualityDecayThreshold pulled into the hash as a latent fix).
    //   v8: chronic-classification minimum-history floor
    //     (`minObservationHistoryFloor`); changes the agreed chronicNonSigners set.
    //   v9: B1/B2 cert witness-pool widened from committee to
    //     `state.eligibleFacilitators - target`. v8 nodes would reject certs that v9 nodes
    //     accept (the eligible-but-non-committee signers), so cluster-wide cold restart is
    //     required. Fixes the wedge where 7-9 valid eviction votes were rejected as
    //     `voter_not_in_committee` when only 4 came from the 9-member committee.
    //   v10: two bundled changes -- (a) BFT-correct eviction cap at
    //     StallDetector.selectEvictionTargets (ceil(n/3) -> committee.size - minQuorum,
    //     equals f for n=3f+1); (b) testnet `min-observation-history-floor` lowered 30 -> 10
    //     so the chronic-non-signer classifier kicks in within ~10 rounds instead of ~30.
    //     Combined fix for the post-restart deadlock where 5+ silent FACILITY_FOREVER
    //     peers stayed in the 9-member committee, blocking 7-of-9 quorum on every round.
    //     v9 nodes have a different floor and cap arithmetic, so cluster-wide cold restart
    //     required.
    //   v11: kick-fast leader graduation. Adds `completed >= 1` to the
    //     leader-eligibility filter at GlobalSnapshotConsensusStateCreator (and currency-l0
    //     mirror). Pre-v11 the filter only checked `participated >= minObservations`, letting
    //     chronic-flaky peers with high participated counts but zero completed rounds keep
    //     getting elected leader -> no proposal -> indefinite stall (peers 890a641e and
    //     c96c3a41 stuck round 3110992 for 36+ minutes). Under the operator's
    //     "kick-fast, recover-slow" policy, peers must demonstrate at least one
    //     completed round before they can lead. Recovery: complete one round as follower ->
    //     re-enter lead-eligible pool. v10 nodes select different leaders, so cluster-wide
    //     cold restart required.
    //   v12: B2 sticky-probation. readmissionCountdown clamps at 0 instead of
    //     auto-clearing when it expires; only AdmissionCertificate can remove a peer from
    //     probation. Empirical motivation: alpha.50 produced ZERO admission certs in 14 hours
    //     because peers exited probation via auto-clear before the StallDetector emission gate's
    //     consecutive-streak threshold fired. v12 closes the bypass -- eviction becomes naturally
    //     sticky and the ACS path becomes load-bearing instead of decorative. v12 nodes carry
    //     readmissionCountdown values that v11 nodes would auto-drop, producing different
    //     `lastOutcome` evolution -> cluster-wide cold restart required.
    //   v13: Facility schema gains `appliedEvictionCerts: List[EvictionCertificate]`.
    //     Lets a node that has assembled a quorum-signed cert apply it at round-start instead
    //     of having to wait for proposal acceptance at the next ordinal. v13 nodes derive
    //     committee = eligible \\ chronicNonSigners \\ probation \\ deferred \\ cert_targets;
    //     v12 nodes derive committee = eligible \\ chronicNonSigners \\ probation \\ deferred.
    //     With even one cert applied, the two committees produce different proof sets, so
    //     v12 cannot safely participate in v13 rounds -- cluster-wide cold restart required.
    //     Closes the testnet ord 3121304 stuck-cluster gap. See
    //     docs/consensus/eviction-cert-deterministic-shrinkage.md.
    //     REVERTED after v13 (commit 5b2ce6722): the current `Facility` has no
    //     `appliedEvictionCerts` field; eviction certs now ride `Proposal.evictionCertificates`
    //     and apply at proposal-acceptance, with QuorumDenominatorShrink (v33) as the liveness
    //     remedy for this wedge class. Kept as deploy history.
    //   v14: Leader rotation band. `selectLeaderWeighted` tier formula changes from
    //     unbounded `participated - completed` to a binary band keyed on
    //     `leaderRotationMinRatioPct` (default 50): peers at or above the ratio threshold all land
    //     in tier 0, below land in tier 1. v13 nodes pick a single ratchet-favored leader; v14
    //     nodes pick a rendezvous-rotated leader across the eligible band. Different leader ->
    //     fork, so cluster-wide cold restart required. Empirical motivation: testnet alpha.72
    //     showed 100% of leadership concentrated on the 2 peers with the lowest
    //     `participated - completed` count even though 6 peers had ratio >= 0.85.
    //   v15: Self-health throttle activated (docs/consensus/self-health-throttle.md).
    //     The Facility builder reads `LocalHealthMonitor.current` and attaches it as
    //     `Facility.selfHealthHint`; the leader aggregates collected hints into
    //     `Proposal.observedSelfHealth`; followers adopt the leader's map on accept and persist
    //     into `outcome.peerSelfHealth`. The next round's `selectLeaderWeighted` reads that map
    //     and demotes Degraded peers to tier 1 / Critical peers to tier 2. v14 nodes do not
    //     populate `observedSelfHealth` (default empty), so a mixed v14/v15 cluster would compute
    //     different leaders on rounds following a v15-led proposal -- bumping anchors the
    //     required cold-restart fence. Jar hash already refuses v14<->v15 peer connections.
    //   v16: Hard quality-score floor on leader candidacy.
    //     Adds `peerViewChanges: SortedMap[PeerId, Long]` to GlobalConsensusOutcome and
    //     CurrencyConsensusOutcome (additive, defaulted to empty so pre-v16 outcomes decode
    //     cleanly). Persisted via `PerPeerOperationalRecord.viewChangesCaused`. The StateAdvancer
    //     credits view-change-caused at round finalization by recomputing the deterministic
    //     leader at each view in `[0, state.viewNumber)` and incrementing the resulting peer.
    //     selectLeaderWeighted pre-filters by integer quality score
    //     `(completed/participated) * (1 - viewChangesCaused/participated)` against
    //     `hardLeaderQualityScorePct` (default 20) before the tier sort, with a fallback to the
    //     full graduated set when fewer than `minLeaderPoolSize` peers survive. Closes the
    //     chronic-leader-loop wedge where peers with high completion but high view-
    //     change rate were tier 1 and `sorted[viewNumber % size]` kept walking through them.
    //     v15 nodes have neither the per-peer view-change credit nor the score-based filter,
    //     so a mixed v15/v16 cluster would compute different leaders the moment any peer has
    //     `viewChangesCaused > 0`. Cold restart required; jar hash gates v15<->v16 peer
    //     connection.
    //   v17: Exact-fraction supermajority quorum. The previous
    //     `quorum-threshold-fraction = 0.67` was a decimal approximation of 2/3 that rounded
    //     up unfavorably for N divisible by 3: `ceil(N * 0.67)` gave 5 for N=6 instead of the
    //     BFT-intended `ceil(2N/3) = 4`. The off-by-one caused wedges where
    //     4-of-6 responsive facilitators failed both Facility quorum AND ViewChangeCertificate
    //     quorum (VCC uses the same threshold), making the cluster unable to either commit OR
    //     view-change out of the stalled round. Operator restart was the only path forward,
    //     and the wedge recurred ~30 minutes later when a chronic peer cycled. testnet config
    //     now sets `quorum-threshold-fraction = 0.6666666666666666` (max-precision Double of
    //     2/3) which makes `ceil(N * fraction)` produce the formal HotStuff threshold for all
    //     N up to ~200. BFT safety preserved: any two quorums of size 4 over N=6 intersect in
    //     2*4 - 6 = 2 >= f+1 replicas for f=1. Tradeoff: implicit f=2 tolerance under the old
    //     0.67 value (5-of-6 tolerates 2 byzantine) is reduced to f=1 (4-of-6 tolerates 1
    //     byzantine). Acceptable because observed silent peers are crash-faulty (network,
    //     JVM hang), not byzantine, and the wedge under crash faults is far more damaging
    //     than the theoretical f=2 tolerance we lose. quorumThresholdFraction is in
    //     deterministicConfigHash so a v16-config and v17-config cluster reject each other at
    //     handshake; jar hash also enforces. Currency-l0 unaffected (defaults to 1.0
    //     unanimity, applies to small metagraph clusters where unanimity is preferred).
    //   v18: Active-set tightening via the recentSigners window. Adds an Option-wrapped
    //     `recentSigners: SortedMap[SnapshotOrdinal, SortedSet[PeerId]]` to
    //     `ConsensusOperationalState`, populated at outcome finalization by reading the
    //     prior round's `Signed[Snapshot].proofs` and trimming to the last
    //     `tighteningWindow` entries. The next-round facilitator candidate pool is
    //     narrowed to peers who signed at least `minParticipationInWindow` of those K
    //     entries (union with grace candidates: `genuinelyNewCandidates` and
    //     `deferredByCountdown`). If the surviving pool is below
    //     `activeFacilitatorFloor`, the filter is bypassed (fallback to full
    //     eligibility) so BFT safety (N >= 3f+1, floor 4) is preserved during cluster-
    //     wide outages. Excluded peers remain eligible for the existing
    //     `chronicReinstatementInterval` rotation so they can re-enter without operator
    //     intervention. Mid-round mutation is intentionally avoided (prior TC-based
    //     eviction attempts forked on local-observation divergence); the next-round
    //     committee is locked at outcome finalization from consensus-agreed inputs.
    //     v17 readers lack the `recentSigners` field and would treat absent as empty,
    //     computing different candidate pools after any round populates the window.
    //     Cold restart required across the cluster.
    //     RETIRED in v19: the per-peer "signed M-of-K to stay in the committee" FILTER
    //     described above was replaced by the multi-committee tier partition (below).
    //     `minParticipationInWindow` became inert config. The `recentSigners` window itself
    //     survives and, as of v22, feeds the tier-demotion hysteresis instead.
    //   v19: Multi-committee architecture plus phase 2 view-from-time timestamp
    //     fields. Three deterministic committees -- Core (Tier 2, gates LIVENESS),
    //     Tier 1 (Witness-eligible), Witness (Tier 0) -- derived per round by
    //     `CommitteeBuilder.build` from the consensus-agreed `lastOutcome.peerTiers`
    //     map. Quorum sites switch from `state.facilitators.value.size` /
    //     `state.roundStartFacilitators.value.size` to `state.coreFacilitators.value.size`,
    //     so the LIVENESS denominator is now the Core committee only. Per-peer tier
    //     is carried via `Option[Int]` on `PerPeerOperationalRecord` (Option-wrapped
    //     for derevo back-compat); absent peers default to Tier 2 (Core) at consume
    //     time. Demotion to Tier 1 fires deterministically ONLY when round N
    //     completed AND the peer was in `roundStartFacilitators[N]` AND was NOT in
    //     `recentSigners[N]`; failed rounds do not cascade-demote. Per-environment
    //     `coreCommitteeSize` floor (testnet 5, mainnet 15, integrationnet 9, dev 3)
    //     ensures the Core committee never shrinks below a viable BFT denominator.
    //     v19 additionally folds in the phase 2 view-from-time anchor: Facility gains
    //     `proposerClockMs: Option[Long]`, ConsensusOperationalState gains
    //     `recentRoundEndTimes: Option[SortedMap[SnapshotOrdinal, Long]]`,
    //     `computeConsensusEndTime` produces the median+clamp from the agreed
    //     Facility set at outcome finalization. v18 nodes do not populate either
    //     of these fields and would derive different committee composition (no
    //     peerTiers), different quorum denominator (Core vs full roundStart), and
    //     no recentRoundEndTimes entries; mixed v18/v19 cluster is unsafe. Cold
    //     restart required across the cluster; jar hash gates v18 <-> v19 peer
    //     connection at handshake.
    //   - v20: `coreCommitteeSize` is now part of `deterministicConfigHash` so a
    //     cluster with divergent Core size values handshake-rejects rather than
    //     silently forking on divergent Core committee derivation. The env-resolved
    //     value is threaded through `ConsensusConfig.coreCommitteeSize: Option[Int]`
    //     (populated at the construction site that resolves
    //     `SnapshotConfig.coreCommitteeSize.get(env)`) and folded into the hash.
    //     v19 nodes lacked the field in the hash and would compute different hashes
    //     for the same Core size config; mixed v19/v20 clusters cannot form.
    //     Cold restart required across the cluster; jar hash gates v19 <-> v20 peer
    //     connection at handshake.
    //     v20 additionally stamps `ConsensusState.initialViewNumber: Int` at round
    //     construction, frozen for the lifetime of the round. Validator-side
    //     `validateProposalVcc` (via the shared `ProposalVccValidator.validate`
    //     helper) reads it to accept a no-VCC proposal at the seed view while still
    //     rejecting a no-VCC proposal once the round has advanced
    //     past the seed (`view{N}_proposal_missing_vcc`). Leader-side proposal-build
    //     gates `vccMissing` / `vccMismatch` and the assembled-VCC fetch on
    //     `viewNumber > initialViewNumber` symmetrically. Pre-alpha.90 the validator
    //     rejected every round-start proposal at `viewNumber > 0` and the cluster
    //     self-wedged on every retry. Not in `deterministicConfigHash` (the field is
    //     on state, not config); behaviour is gated by the schema-version anyway since
    //     v19 and v20 cannot interoperate. Operator dashboards grep on
    //     `view{N}_proposal_missing_vcc` and `vcc_view_mismatch` rejection codes --
    //     the latter is the alpha.90 issue 2 stale-VCC view-mismatch gate.
    //     v21: `viewInterval` raised 30s -> 60s (deploy unit A). Pure value change; it
    //     alters `deterministicConfigHash` (viewIntervalMs is folded in below), so the
    //     real cross-cluster gate is the config-hash + jar-hash mismatch at handshake.
    //     This version bump is an audit anchor only, not the gate.
    //     v22: testnet Core committee floor 3 -> 2 plus sustained-silence demotion
    //     hysteresis (deploy unit B; a Core peer is demoted only after it is absent from
    //     the most-recent `TierTransitions.DemotionConsecutiveMisses` signer sets, not on a
    //     single missed signature). `coreCommitteeSize` is in `deterministicConfigHash`, so
    //     the config-hash + jar-hash are the gate; v22 is the audit anchor for B.
    //     v23: leader selection also narrows the Core leader pool to peers present in the
    //     most-recent `TierTransitions.DemotionConsecutiveMisses` signer sets, when that
    //     leaves at least `minLeaderPoolSize` candidates. Deterministic: `recentSigners`
    //     is signed outcome state. This does not shrink Core or change quorum; it only
    //     keeps sustained non-signers out of the leader slot until certified
    //     timeout/shrink replaces local Core-gate inference.
    //     v24: first-class TimeoutVote / TimeoutCertificate collection. TC is signed and
    //     parent-hash anchored but intentionally inert in v24: it assembles/stores evidence
    //     only and does not advance view, shrink Core, or validate proposals.
    //     v25: TC becomes active. Delayed TC apply advances the view deterministically from
    //     signed quorum evidence (atomic on viewNumber==fromView); leaders carry VCC OR TC on
    //     view>seed proposals; followers validate TC-carried proposals (view linkage, quorum,
    //     parent/facilitator hash, witness pool, divergent + carry-forward highest-QC). `Proposal`
    //     gains `timeoutCertificate: Option[TimeoutCertificate]` -- a wire change, so deploy as a
    //     coordinated cold L0 restart. As always the jar/config-hash mismatch at handshake is the
    //     real gate; this bump is the audit anchor. Not yet: certified shrink/yield, TC gossip
    //     rehydration.
    //     v26: active-facilitator expansion. The recent-signer-only admission filter now keeps
    //     recent signers first, then fills toward `activeFacilitatorTarget` with deterministic
    //     peerQuality-ranked candidates, capped by `activeFacilitatorMax`. This changes the
    //     active facilitator set and therefore quorum/signature behavior, so it is
    //     consensus-critical and requires a coordinated cold L0 restart.
    //     v27: bounded integral consensus peer controller. Active admission scores are
    //     updated from finalized signer/responder/self-health evidence and persisted in
    //     peerHistory so peers cannot jump into rewards-affecting roles from a one-round
    //     Ready blip.
    //     v28: controller adds accepted-TC voter evidence. A peer active in a round that
    //     finalized with a TimeoutCertificate but absent from that accepted/finalized TC
    //     voter set receives `activeAdmissionTimeoutMissingPenalty`. The voter set is
    //     carried through round state/outcome, never read from local timeout caches.
    //     v29: signed controller score records are emitted over a canonical key set with
    //     explicit zero scores, eliminating absent-vs-zero peerHistory.perPeer byte drift.
    //     Active admission also adds a bounded probation lane that fills unused expansion
    //     slots with deterministic, non-disqualified candidates so unknown peers can earn
    //     finalized score evidence.
    //     v30: alpha.129 liveness hardening. Certified timeout shrink now evaluates on the
    //     first timeout certificate instead of only later views, allowing a quorum TC to
    //     reduce a stuck 4-active round to the TC voters when the old quorum floor is still
    //     preserved. Active expansion also becomes cadence-limited by finalized ordinal so
    //     testnet can keep growing without adding a new active denominator every round.
    //     v31: probationary active expansion is forced non-Core for the round. Probation
    //     peers can receive facilities and sign as Tier 1, but cannot carry a prior Core
    //     tier or satisfy Core-floor promotion until the integral controller graduates them.
    //     v32: controller-evidence read side activates (stage 4). Active-admission scores and
    //     quality derive from the signed `controllerEvidence` window (carried maps remain only
    //     as the bootstrap fallback while the window is empty); cert-anchored `penaltyUntil`
    //     joins eligibility filtering; `penaltyDurationOrdinals` enters
    //     `deterministicConfigHash`. Signed-bytes shape changed: peerHistory reinstated with
    //     an evidence-only payload (perPeer empty, recentRoundEndTimes omitted,
    //     controllerEvidence + penaltyUntil signed), so deploy as a coordinated cold L0
    //     restart. As always the jar/config-hash mismatch at handshake is the real gate; this
    //     bump is the audit anchor.
    //     v33: escalating quorum-denominator shrink (QuorumDenominatorShrink). After
    //     `quorumShrinkActivationViews` viewInterval units of silence since the parent
    //     outcome closed, the REQUIRED quorum for phase/VCC/TC feasibility at the stuck key
    //     escalates downward (stage 1 to the anchor majority, stage 2 slowly to the hard
    //     floor 2), with shrunken-margin votes restricted to the deterministic anchor
    //     (latest controllerEvidence completedSigners intersected with the frozen
    //     round-start committee). The persisted committee and facilitatorsHash are never
    //     touched. Targets the post-restart 3-of-6 mathematical wedge (ord 3150197 /
    //     task #123 / the Apr-29 deadlock class). `quorumShrinkActivationViews` enters
    //     `deterministicConfigHash`; default 0 keeps the rung disabled.
    consensusSchemaVersion: Int = 33,
    // Local-only RUNTIME knob: size of the dedicated work-stealing pool that runs the
    // ConsensusEventLoop main command-consume fiber. Pinning the FSM onto its own pool
    // isolates round-timing from HTTP serving load (a burst of snapshot fetches, even with
    // PR-1's streaming heap discipline, can otherwise contend with consensus on the global
    // compute pool). 0 means "use the default global runtime" (legacy behaviour, useful for
    // tests that build the loop without a dedicated EC). Non-zero values are clamped to >=1
    // at the construction site.
    //
    // NOT in `deterministicConfigHash`: thread-pool sizing is per-node performance tuning,
    // not consensus protocol. Operators may run different values without forking.
    //
    // Default 2: covers consume + occasional pinned downstream work without monopolising
    // cores on the typical 4-8 vCPU validator. Larger pools rarely help because the consume
    // loop is single-threaded by construction; the pool size matters only for the fanned-out
    // round handlers that elect to shift back via `evalOn`.
    consensusDispatcherThreads: Int = 2,
    // v20: env-resolved Core committee size, populated by the consensus construction site
    // (GlobalSnapshotConsensus / CurrencySnapshotConsensus) from
    // `SnapshotConfig.coreCommitteeSize.get(env).map(_.value).getOrElse(3)` BEFORE
    // `deterministicConfigHash` is read. Threading the env-resolved value through here lets
    // the hash include it without restructuring the HOCON layer or duplicating env resolution.
    // The `Option` default is `None` so unit tests / construction sites that don't go through
    // the snapshot wiring continue to compile; the hash treats `None` as the default `3` (the
    // dev value matching the pre-v20 `getOrElse(3)` behaviour). Consensus-critical because the
    // LIVENESS quorum threshold is computed against `coreFacilitators.value.size`; divergent
    // operator values would derive divergent Core committees and silently fork. Now in
    // `deterministicConfigHash` so a v19 (no-hash) and v20 (with-hash) cluster compute
    // different hashes and reject each other at the Facility handshake. Jar hash also gates
    // peer connection.
    coreCommitteeSize: Option[Int] = None,
    // v33 quorum-denominator shrink rung (QuorumDenominatorShrink): number of `viewInterval`
    // units of wall silence since the parent outcome's `consensusEndTime` after which the
    // escalating quorum shrink begins. `0` (default) disables the rung entirely. Env-resolved
    // at the consensus construction site from `SnapshotConfig.quorumShrinkActivationViews.get(env)`
    // (the coreCommitteeSize pattern); testnet runs an aggressive value, mainnet stays disabled.
    // Measured in views rather than abandonment counts deliberately: one abandonment cycle is
    // ~1 viewInterval of silence, but the local abandonment counters are node-local Refs that
    // reset on restart and must never gate cross-node acceptance (the alpha.104 lesson). The
    // ViewFromTime anchor gives the same escalation cadence from data all nodes share.
    // Consensus-critical: changes cert/phase acceptance thresholds at the stuck key, so it is
    // included in `deterministicConfigHash` -- divergent operator values handshake-reject.
    quorumShrinkActivationViews: Int = 0
  ) {

    /** Deterministic hash of consensus-critical config values.
      *
      * All nodes in a consensus round MUST have the same config to produce the same results. This hash is included in Facility declarations
      * so that config divergence is detected immediately during the CollectingFacilities phase, rather than causing mysterious forks
      * downstream.
      *
      * '''Consensus-critical fields''' (included in hash):
      *   - `maxFacilitatorCount`: determines eligible facilitator list size and rendezvous hashing
      *   - `maxStallCycles`: affects when rounds are abandoned (triggers recovery)
      *   - `removalPenaltyRounds`: affects facilitator eligibility after eviction
      *   - `candidateDeferralRounds`: affects how long new candidates observe before facilitating
      *   - `readmissionProbationRounds`: affects B2 re-admission cadence for peers whose penalty expired
      *   - `quorumThresholdFraction`: determines how many declarations needed to advance phases
      *   - `exponentialPenaltyBase`: base for scaling removalPenaltyRounds per repeat eviction
      *   - `maxRemovalPenaltyRounds`: cap on total penalty so it doesn't overflow Int
      *   - `minParticipationObservations`: threshold at which chronic non-signer filter kicks in
      *   - `minParticipationRatio`: ratio below which a peer is excluded from the committee
      *   - `leaderRotationMinRatioPct`: leader-rotation band threshold (tier 0 vs tier 1 inside the pool)
      *   - `hardLeaderQualityScorePct`: hard floor on the consensus-agreed quality score (peers below excluded from leader pool)
      *   - `minLeaderPoolSize`: fallback threshold when too few peers clear the hard floor
      *   - `minObservationHistoryFloor`: minimum participated count before chronic classification can fire
      *   - `forceViewChangeAbandonments`: defensive force-VCV threshold (bypasses missing-still-responsive gate after N same-key abandons)
      *   - `tighteningWindow`: size of the rolling `recentSigners` window; as of v22 it feeds the tier-demotion hysteresis (LIVE)
      *   - `minParticipationInWindow`: INERT (dead config) -- parameterized the retired v19 active-set tightening filter; kept in the hash
      *     only to avoid a schema change, read by no logic (the v22 hysteresis uses `TierTransitions.DemotionConsecutiveMisses`)
      *   - `activeFacilitatorFloor`: active-admission emergency bypass and rollback / ready-participation floor
      *   - `activeFacilitatorTarget` / `activeFacilitatorMax`: active facilitator expansion and cap
      *   - `bootstrapDeclarationTimeoutMultiplier`: affects phase-transition timing during bootstrap
      *   - `coreCommitteeSize`: env-resolved Core committee floor; changes Core derivation and the LIVENESS quorum denominator. Populated
      *     by the consensus construction site from `SnapshotConfig.coreCommitteeSize.get(env)` (defaults to dev value 3 when absent). v20
      *     pulls this into the hash so divergent operator values handshake-reject rather than silently forking.
      *
      * '''Non-critical fields''' (excluded — affect timing/performance, not deterministic outcomes):
      *   - `timeTriggerInterval`, `declarationTimeout`, `lockDuration`, `reStallTimeout`, `noProgressTimeout`: timing only
      *   - `facilitiesTimeoutMultiplier`, `proposalsTimeoutMultiplier`, `signaturesTimeoutMultiplier`: timing multipliers only
      *   - `maxRoundDuration`: safety net, not consensus logic
      *   - `declarationRangeLimit`, `eventCutter`: event filtering, not consensus decisions
      *
      * '''qualityDecayThreshold reclassified.''' Previously documented as local-only and excluded from this hash. In reality it mutates the
      * consensus-agreed `lastOutcome.peerQuality` field at advancer:330 (decay halves both completed and participated counters when any
      * peer's participated exceeds threshold). Two nodes with different threshold values would compute different `peerQuality` after any
      * decay event, producing latent divergence. Now included in the hash. Pre-existing latent bug fix bundled with the schema bump.
      *
      * IMPORTANT: When adding new fields to ConsensusConfig, evaluate whether they affect consensus determinism. If the field changes what
      * peers decide (facilitator selection, quorum logic, voting thresholds), add it to the hash string below. If it only affects timing or
      * performance, exclude it.
      */
    lazy val deterministicConfigHash: Hash = {
      val configString =
        s"maxFacilitatorCount=${maxFacilitatorCount.map(_.value)}," +
          s"maxStallCycles=$maxStallCycles," +
          s"removalPenaltyRounds=$removalPenaltyRounds," +
          s"candidateDeferralRounds=$candidateDeferralRounds," +
          s"readmissionProbationRounds=$readmissionProbationRounds," +
          s"quorumThresholdFraction=$quorumThresholdFraction," +
          s"exponentialPenaltyBase=$exponentialPenaltyBase," +
          s"maxRemovalPenaltyRounds=$maxRemovalPenaltyRounds," +
          s"minParticipationObservations=$minParticipationObservations," +
          s"minParticipationRatio=$minParticipationRatio," +
          // Leader-rotation band threshold. Mutates the agreed leader of every
          // round; divergent operator values would silently fork the cluster.
          s"leaderRotationMinRatioPct=$leaderRotationMinRatioPct," +
          // Hard quality-score floor + pool-size fallback. Together these
          // determine which peers are leader candidates; divergent operator values would
          // produce different leader pools and silently fork.
          s"hardLeaderQualityScorePct=$hardLeaderQualityScorePct," +
          s"minLeaderPoolSize=$minLeaderPoolSize," +
          // Chronic-classification floor. Changes the agreed chronicNonSigners
          // set; divergent operator values would produce silently-divergent committee composition.
          s"minObservationHistoryFloor=$minObservationHistoryFloor," +
          // Defensive force-VCV threshold. Different operator values would
          // produce divergent VCV-emission timing, splitting the cluster across (fromView, toView)
          // pairs and starving VCC assembly.
          s"forceViewChangeAbandonments=$forceViewChangeAbandonments," +
          // v19 phase 2 view-from-time anchor divisor. Mutates the round-start `viewNumber` (and
          // therefore the initial leader) under wall-clock progress; divergent operator values would
          // produce divergent leader selection from the same parent snapshot and silently fork.
          s"viewIntervalMs=${viewInterval.toMillis}," +
          // Active-set tightening: three parameters together determine the next-round
          // committee membership; divergent operator values would produce silently-
          // divergent facilitator sets and fork the cluster.
          s"tighteningWindow=$tighteningWindow," +
          s"minParticipationInWindow=$minParticipationInWindow," +
          s"activeFacilitatorFloor=$activeFacilitatorFloor," +
          s"activeFacilitatorTarget=${activeFacilitatorTarget.getOrElse(coreCommitteeSize.getOrElse(3))}," +
          s"activeFacilitatorMax=${activeFacilitatorMax.map(_.toString).getOrElse("none")}," +
          s"activeAdmissionPromoteThreshold=$activeAdmissionPromoteThreshold," +
          s"activeAdmissionRetainThreshold=$activeAdmissionRetainThreshold," +
          s"activeAdmissionDemoteThreshold=$activeAdmissionDemoteThreshold," +
          s"activeAdmissionMaxScore=$activeAdmissionMaxScore," +
          s"activeAdmissionSignatureReward=$activeAdmissionSignatureReward," +
          s"activeAdmissionResponderReward=$activeAdmissionResponderReward," +
          s"activeAdmissionMissedActivePenalty=$activeAdmissionMissedActivePenalty," +
          s"activeAdmissionTimeoutMissingPenalty=$activeAdmissionTimeoutMissingPenalty," +
          s"activeAdmissionEvictedPenalty=$activeAdmissionEvictedPenalty," +
          s"activeAdmissionDegradedPenalty=$activeAdmissionDegradedPenalty," +
          s"activeAdmissionCriticalPenalty=$activeAdmissionCriticalPenalty," +
          s"activeAdmissionPassiveDecay=$activeAdmissionPassiveDecay," +
          s"activeAdmissionMaxExpansionPerRound=$activeAdmissionMaxExpansionPerRound," +
          s"activeAdmissionExpansionIntervalRounds=$activeAdmissionExpansionIntervalRounds," +
          // Bounded probation re-entry lane: changes the active committee -> roundStartFacilitators
          // -> facilitatorsHash, so divergent operator values must handshake-reject rather than
          // silently fork. Env-resolved to 0 (inert) when absent for an environment.
          s"activeAdmissionMinProbationReentrySlots=$activeAdmissionMinProbationReentrySlots," +
          // Recent-signer pool lookback depth: changes the active committee -> roundStartFacilitators
          // -> facilitatorsHash, so divergent operator values must handshake-reject rather than
          // silently fork. Floored to DemotionConsecutiveMisses (3) at the construction site.
          s"activeAdmissionRecentSignerWindow=$activeAdmissionRecentSignerWindow," +
          s"chronicReinstatementInterval=$chronicReinstatementInterval," +
          s"lockOnVoteProtocolVersion=$lockOnVoteProtocolVersion," +
          s"bootstrapCompleteProofsThreshold=$bootstrapCompleteProofsThreshold," +
          s"bootstrapDeclarationTimeoutMultiplier=$bootstrapDeclarationTimeoutMultiplier," +
          // v7 (codex turn 2 fix): qualityDecayThreshold mutates consensus-agreed peerQuality
          // (advancer:330 decay path); must be in the hash so divergent operator values can't
          // produce silently-divergent peerQuality maps.
          s"qualityDecayThreshold=$qualityDecayThreshold," +
          // v20: env-resolved Core committee size. Divergent operator values would produce
          // divergent Core committee derivation and silently fork. Treated as the dev default
          // `3` when absent (matches the pre-v20 `SnapshotConfig.coreCommitteeSize.get(env)
          // .map(_.value).getOrElse(3)` resolution at the consensus construction site).
          s"coreCommitteeSize=${coreCommitteeSize.getOrElse(3)}," +
          // v32 (stage 4): cert-anchored penalty duration. Read by the StateCreators'
          // penaltyUntil eligibility filtering and the advancers' penaltyUntil writes;
          // divergent operator values would derive divergent committees and silently fork.
          s"penaltyDurationOrdinals=$penaltyDurationOrdinals," +
          // v33: quorum-denominator shrink activation threshold. Changes the effective
          // cert/phase acceptance quorum at a wedged key; divergent operator values would
          // make one node accept a shrunken VCC/TC that another rejects.
          s"quorumShrinkActivationViews=$quorumShrinkActivationViews," +
          // v7 schema-version anchor; explicit fence against mixed-wire-version cluster joins.
          s"consensusSchemaVersion=$consensusSchemaVersion"
      Hash.fromBytes(configString.getBytes("UTF-8"))
    }
  }

  case class EventCutterConfig(
    maxBinarySizeBytes: PosInt,
    maxUpdateNodeParametersSize: PosInt
  )

  case class SnapshotBinarySenderTimeoutsConfig(
    routes: FiniteDuration,
    client: FiniteDuration
  )

  case class SnapshotServingConfig(
    maxConcurrentPublic: Int = 20,
    retryAfterSeconds: Long = 2,
    // Per-client-IP rate limit. 0 requests or 0s window disables the limit entirely
    // (preserves legacy behaviour when config omits the new fields).
    perIpMaxRequestsPerWindow: Int = 0,
    perIpWindow: FiniteDuration = 1.minute,
    perIpRetryAfterSeconds: Long = 5,
    // IPs that bypass the per-IP rate AND bandwidth limits. Comma-separated string for env-var
    // ergonomics (CL_SNAPSHOT_PER_IP_ALLOWLIST="ip1,ip2"). Used for trusted infra such as the
    // snapshot streaming consumer, internal monitoring, and known source nodes that legitimately
    // exceed the per-IP cap. Empty string disables the allowlist (default behaviour: every IP
    // is rate-limited).
    perIpAllowlist: String = "",
    // Per-client-IP BANDWIDTH limit applied only to heavyweight snapshot routes
    // (combined-stream + per-ordinal checkpoint stream). 0 bytes disables the limit.
    //
    // Default 300 MB / 1 minute = 5 MiB/s sustained per IP. A legitimate snapshot streamer
    // pulls one 72 MB combined snapshot per round (~22-50s/round) ~= 1.5 MiB/s, so this
    // gives ~3-4x headroom for legitimate parallel reads while throttling bulk pollers.
    //
    // See PerIpBandwidthLimitMiddleware for mechanism + caveats.
    perIpMaxBytesPerWindow: Long = 0L,
    // Optional longer-window byte budget for sustained-poller control. This is an integral
    // term layered on top of `perIpMaxBytesPerWindow`: the short window permits normal burst
    // behavior, while the long window caps aggregate egress over several minutes. 0 disables.
    perIpMaxBytesPerLongWindow: Long = 0L,
    perIpLongWindow: FiniteDuration = 5.minutes,
    // Optional node-wide long-window byte budget for heavyweight snapshot routes. Unlike the
    // per-IP budget, this caps aggregate egress from the node and is not bypassed by the per-IP
    // allowlist. 0 disables.
    maxBytesPerLongWindow: Long = 0L,
    longWindow: FiniteDuration = 5.minutes,
    perIpBandwidthRetryAfterSeconds: Long = 5,
    // Optional adaptive backoff for heavyweight snapshot routes. Unlike the hard per-IP byte
    // cap, this can also apply to allowlisted clients: trusted consumers are not exempt from
    // receiving a Retry-After hint when they poll too aggressively. Disabled by default so
    // networks opt in per environment.
    adaptiveBackoffEnabled: Boolean = false,
    adaptiveBackoffMaxRequestsPerWindow: Int = 0,
    adaptiveBackoffMaxBytesPerWindow: Long = 0L,
    adaptiveBackoffWindow: FiniteDuration = 5.minutes,
    adaptiveBackoffBaseRetryAfterSeconds: Long = 3,
    adaptiveBackoffMaxRetryAfterSeconds: Long = 300,
    adaptiveBackoffPenaltyDecay: FiniteDuration = 5.minutes,
    adaptiveBackoffApplyToAllowlist: Boolean = false,
    // Route-scoped bound on simultaneous heavy snapshot serves (currently
    // `/latest/combined/stream` + `/{ordinal}?full=true`). Layered INSIDE the existing
    // public-middleware chain so it applies regardless of whether the request is anonymous
    // or peer-authenticated. When saturated the route returns 503 with a Retry-After
    // header instead of queuing the handler. Tuned to match the storage-layer
    // `concurrentStreams` permit (default 4) plus a small slack -- the route fast-fails
    // before the disk-stream semaphore would block, so slow consumers don't accumulate
    // ahead of the disk read.
    heavyRouteConcurrency: PosInt = PosInt(6)
  )

  case class SnapshotTimeoutsConfig(
    routes: FiniteDuration,
    client: FiniteDuration
  )

  case class RouteRateLimiterConfig(
    public: FiniteDuration,
    peerToPeer: FiniteDuration
  )

  object RouteRateLimiterConfig {
    def empty(): RouteRateLimiterConfig =
      RouteRateLimiterConfig(
        0.second,
        0.second
      )
  }
  case class ClickHouseAppConfig(
    maxRetries: Int,
    maxQueueSize: Int,
    retryBaseDelay: FiniteDuration,
    batchSize: Int,
    flushInterval: FiniteDuration,
    retentionPeriodInDays: Int,
    errorPauseDuration: FiniteDuration,
    host: Option[String],
    user: Option[String],
    password: Option[String],
    logsTableName: Option[String],
    metricsTableName: Option[String],
    port: Option[Int],
    database: Option[String],
    protocol: Option[String]
  )

  case class SnapshotConfig(
    consensus: ConsensusConfig,
    maxFacilitatorCount: Map[AppEnvironment, PosInt] = Map.empty,
    // v19 multi-committee minimum Core size, keyed by AppEnvironment. Targets observed
    // committee sizes: testnet ~14 peers -> Core 5, mainnet ~150 peers -> Core 15,
    // integrationnet ~10 peers -> Core 9, dev (single-node test rigs) -> Core 3.
    // Consensus-critical because the LIVENESS quorum threshold is computed against
    // `coreFacilitators.value.size`; divergent operator values would derive divergent
    // Core committees and silently fork. The jar hash already gates peer connections.
    //
    // v20 update: the env-resolved value is now also folded into `ConsensusConfig.coreCommitteeSize`
    // at the consensus construction site, which in turn folds into `deterministicConfigHash`,
    // so a Facility-time handshake refusal is the second line of defence against divergent
    // operator values (in addition to the jar hash). The `Map[AppEnvironment, PosInt]` shape is
    // preserved -- env resolution still happens at the construction site
    // (GlobalSnapshotConsensus / CurrencySnapshotConsensus); only the resolved scalar is
    // additionally threaded into the hash.
    coreCommitteeSize: Map[AppEnvironment, PosInt] = Map.empty,
    // v33 quorum-denominator shrink activation threshold, keyed by AppEnvironment (the
    // coreCommitteeSize pattern: env resolution happens once at the construction site and the
    // resolved scalar is threaded into `ConsensusConfig.quorumShrinkActivationViews`, which
    // folds into `deterministicConfigHash`). 0 (or an absent env) DISABLES the rung for that
    // environment, matching the resolved scalar's `<= 0` disable. Same Int shape as the sibling
    // activeAdmission* knobs below. Testnet runs an aggressive value; mainnet/integrationnet/dev
    // are 0 -- the deep stage trades partition safety for liveness and is opted into per env.
    quorumShrinkActivationViews: Map[AppEnvironment, Int] = Map.empty,
    // Bounded probation re-entry lane, keyed by AppEnvironment (the coreCommitteeSize pattern: env
    // resolution happens once at the consensus construction site and the resolved scalar is threaded
    // into `ConsensusConfig.activeAdmissionMinProbationReentrySlots`, which folds into
    // `deterministicConfigHash`). An absent env entry means the lane is DISABLED for that
    // environment (resolved scalar 0). Public GL0 environments configure one Core-sized cohort;
    // responsive climbers retain bounded priority until they reach the retain band. `Int` (not
    // `PosInt`) keeps 0 available as an explicit disable.
    activeAdmissionMinProbationReentrySlots: Map[AppEnvironment, Int] = Map.empty,
    // Recent-signer pool lookback depth (in ordinals), keyed by AppEnvironment (the coreCommitteeSize
    // pattern: env resolution happens once at the consensus construction site and the resolved scalar
    // is threaded into `ConsensusConfig.activeAdmissionRecentSignerWindow`, which folds into
    // `deterministicConfigHash`). Controls how long an intermittently-signing peer keeps a sticky
    // active seat before churning through expansion/reserve. An absent env entry resolves to the
    // DemotionConsecutiveMisses floor (3 = the pre-change lookback). Testnet widens to the full
    // persisted `recentSigners` window (`tighteningWindow`); mainnet/dev/integrationnet absent on
    // purpose -- widening is an active-set/reward-breadth change to opt into per environment.
    activeAdmissionRecentSignerWindow: Map[AppEnvironment, Int] = Map.empty,
    // Active-set growth target, keyed by AppEnvironment (the coreCommitteeSize pattern: env
    // resolution happens once at the consensus construction site and the resolved value is threaded
    // into `ConsensusConfig.activeFacilitatorTarget`, which folds into `deterministicConfigHash`).
    // This is the admission deficit gate's threshold (`ActiveFacilitatorAdmission
    // .activeAdmissionTarget`): the advancers wait for expansion certificates and the StallDetector
    // emits expansion AdmissionVotes only while `roundStartFacilitators.size` is below it.
    // INVARIANT: must EXCEED the environment's `coreCommitteeSize` (Core floor), or the feeder
    // closes before Core can reach its floor and quorum feasibility wedges (v4.1.0: base scalar 7
    // vs integrationnet floor 9). Scaled 2c+1 from the environment's Core size in the conf files.
    // Absent env entries preserve the ConsensusConfig scalar resolution.
    activeFacilitatorTarget: Map[AppEnvironment, Int] = Map.empty,
    // Active-set hard cap, keyed by AppEnvironment (same pattern; folds into the hash via
    // `ConsensusConfig.activeFacilitatorMax`). Bounds the sticky recent-signer pool and the
    // probation re-entry headroom. INVARIANT: must be >= the environment's `coreCommitteeSize` --
    // the pre-scaling base scalar 13 was BELOW mainnet's Core floor 15, capping the active set
    // under the floor: a guaranteed quorum-feasibility wedge. Scaled 4c+1 from Core size.
    activeFacilitatorMax: Map[AppEnvironment, Int] = Map.empty,
    inMemoryCapacity: NonNegLong,
    snapshotPath: Path,
    snapshotInfoPath: Path,
    incrementalTmpSnapshotPath: Path,
    incrementalPersistedSnapshotPath: Path,
    calculatedStatePath: Path,
    globalSnapshotsWithStatePath: Path,
    globalSnapshotsWithStateDeltasPath: Path,
    maxGlobalSnapshotsWithStateStored: PosLong,
    maxGlobalSnapshotsWithStateDeltasStored: PosLong,
    combinedSnapshotCheckpointPath: Path
  )

  case class HttpClientConfig(
    timeout: FiniteDuration,
    idleTimeInPool: FiniteDuration
  )

  // Ember-level connection cap. Backstops the per-route ConcurrencyLimitMiddleware from
  // PR-1: a buggy or hostile client cannot open unlimited concurrent connections regardless
  // of which route they target, so fd exhaustion and excessive concurrent handler scheduling
  // are bounded at the server.
  //
  // Alpha.95: the field's static default is 100 but the operational value is environment-
  // resolved at boot via `HttpConfig.envResolved` -- testnet/intnet/mainnet each have different
  // peer-count expectations so a single number cannot fit all. PR-1's blanket 100 default
  // (alpha.76 commit `2cbff6aee`) caused the May 17 chain-growth regression on testnet:
  // 13+ peers each running gossip/observation/snapshot-pull/consensus-retransmit in parallel
  // saturated the p2p socket, intermittently dropping calls to community peers and shrinking
  // the eligible facilitator pool from ~10 to ~6 then ~3. The blanket cap was the wrong knob;
  // per-route shaping (PerIpRateLimitMiddleware + heavyRouteConcurrency) is the right knob.
  //
  // Static 100 stays as the no-environment-resolved fallback (smaller than any real env, so
  // catches misconfiguration loudly rather than silently using a too-high cap).
  case class HttpServerConfig(
    host: Host,
    port: Port,
    shutdownTimeout: FiniteDuration,
    maxConnections: PosInt = PosInt(100)
  )

  case class HttpConfig(
    externalIp: Host,
    client: HttpClientConfig,
    publicHttp: HttpServerConfig,
    p2pHttp: HttpServerConfig,
    cliHttp: HttpServerConfig,
    // Compiled-in per-environment defaults for `maxConnections`, populated by each module's
    // `cli/http.scala` from `HttpMaxConnectionsDefaults`. NOT loaded from HOCON -- HttpConfig
    // is built by the CLI flow (see `CliMethod.scala`), not by `SharedConfigReader`. Operators
    // who need a value different from the compiled default use the corresponding override
    // CLI flag / env var (see `*MaxConnectionsOverride` below), which has higher precedence.
    //
    // Public listener scales with end-client load (block explorers, wallets, RPC users);
    // mainnet needs the highest ceiling.
    publicMaxConnections: Map[AppEnvironment, PosInt] = Map.empty,
    // P2P listener scales with peer count -- every other validator opens persistent and
    // burst connections (gossip, observation, snapshot pull, consensus push-rumor). This
    // is the field whose 100 ceiling caused the May 17 testnet regression.
    p2pMaxConnections: Map[AppEnvironment, PosInt] = Map.empty,
    // CLI listener is localhost-only and only the operator talks to it. No need to scale
    // with cluster size; the field is here for symmetry / future use.
    cliMaxConnections: Map[AppEnvironment, PosInt] = Map.empty,
    // Explicit operator overrides via CLI flag / env var:
    //   --public-max-connections / CL_PUBLIC_HTTP_MAX_CONNECTIONS
    //   --p2p-max-connections    / CL_P2P_HTTP_MAX_CONNECTIONS
    //   --cli-max-connections    / CL_CLI_HTTP_MAX_CONNECTIONS
    // When `Some`, the corresponding listener uses this value regardless of the env-map
    // default. None means "no operator override, fall through to compiled env default,
    // then to `HttpServerConfig.maxConnections` field default".
    publicMaxConnectionsOverride: Option[PosInt] = None,
    p2pMaxConnectionsOverride: Option[PosInt] = None,
    cliMaxConnectionsOverride: Option[PosInt] = None
  ) {

    /** Returns a copy of this HttpConfig with each listener's `maxConnections` resolved against the supplied environment.
      *
      * Precedence (highest first):
      *   1. Explicit operator override via CLI flag / env var (`*MaxConnectionsOverride`). 2. Compiled per-environment default from
      *      `HttpMaxConnectionsDefaults` (populated by each module's CLI into the env maps). 3. The underlying
      *      `HttpServerConfig.maxConnections` field default (`PosInt(100)`).
      *
      * Called from each Main once `AppConfig` is loaded and before `MkHttpServer.newEmber` binds the listeners.
      */
    def envResolved(env: AppEnvironment): HttpConfig = {
      def resolve(listener: HttpServerConfig, override_ : Option[PosInt], m: Map[AppEnvironment, PosInt]): HttpServerConfig =
        override_
          .orElse(m.get(env))
          .fold(listener)(v => listener.copy(maxConnections = v))
      copy(
        publicHttp = resolve(publicHttp, publicMaxConnectionsOverride, publicMaxConnections),
        p2pHttp = resolve(p2pHttp, p2pMaxConnectionsOverride, p2pMaxConnections),
        cliHttp = resolve(cliHttp, cliMaxConnectionsOverride, cliMaxConnections)
      )
    }
  }

  /** Compiled-in per-environment defaults for HTTP listener `maxConnections`. Each module's `cli/http.scala` populates the corresponding
    * `HttpConfig` Map field with these values. Operators tune per-deployment via the explicit CLI flag / env var (see
    * `HttpConfig.envResolved` scaladoc), NOT via HOCON -- HttpConfig is built by the CLI flow, not the HOCON reader.
    */
  object HttpMaxConnectionsDefaults {
    val publicHttp: Map[AppEnvironment, PosInt] = Map(
      AppEnvironment.Dev -> PosInt(200),
      AppEnvironment.Testnet -> PosInt(1000),
      AppEnvironment.Integrationnet -> PosInt(2000),
      AppEnvironment.Mainnet -> PosInt(4000)
    )
    val p2pHttp: Map[AppEnvironment, PosInt] = Map(
      AppEnvironment.Dev -> PosInt(200),
      AppEnvironment.Testnet -> PosInt(1000),
      AppEnvironment.Integrationnet -> PosInt(2000),
      AppEnvironment.Mainnet -> PosInt(4000)
    )
    val cliHttp: Map[AppEnvironment, PosInt] = Map(
      AppEnvironment.Dev -> PosInt(100),
      AppEnvironment.Testnet -> PosInt(100),
      AppEnvironment.Integrationnet -> PosInt(100),
      AppEnvironment.Mainnet -> PosInt(100)
    )
  }

  case class CollateralConfig(
    amount: Amount
  )

  case class DelegatedStakingConfig(
    minRewardFraction: RewardFraction,
    maxRewardFraction: RewardFraction,
    maxMetadataFieldsChars: PosInt,
    maxTokenLocksPerAddress: PosInt,
    minTokenLockAmount: PosLong,
    withdrawalTimeLimit: Map[AppEnvironment, EpochProgress]
  )

  case class EmissionConfigEntry(
    epochsPerYear: PosLong,
    asOfEpoch: EpochProgress,
    iTarget: NonNegFraction,
    iInitial: NonNegFraction,
    lambda: NonNegFraction,
    iImpact: NonNegFraction,
    totalSupply: Amount,
    dagPrices: Map[EpochProgress, NonNegFraction],
    epochsPerMonth: NonNegLong
  )

  case class ProgramsDistributionConfig(
    weights: Map[Address, NonNegFraction],
    validatorsWeight: NonNegFraction,
    delegatorsWeight: NonNegFraction
  )

  case class OneTimeReward(epoch: EpochProgress, address: Address, amount: TransactionAmount)

  sealed trait RewardsConfig

  case class ClassicRewardsConfig(
    programs: EpochProgress => ProgramsDistributionConfig,
    rewardsPerEpoch: Map[EpochProgress, Amount],
    oneTimeRewards: List[OneTimeReward]
  ) extends RewardsConfig

  case class DelegatedRewardsConfig(
    flatInflationRate: NonNegFraction,
    emissionConfig: Map[AppEnvironment, EpochProgress => EmissionConfigEntry],
    percentDistribution: Map[AppEnvironment, EpochProgress => ProgramsDistributionConfig],
    oneTimeRewards: Map[AppEnvironment, List[OneTimeReward]],
    priceOracleEpoch: Map[AppEnvironment, EpochProgress]
  ) extends RewardsConfig

  case class TrustStorageConfig(
    ordinalTrustUpdateInterval: NonNegLong,
    ordinalTrustUpdateDelay: NonNegLong,
    seedlistInputBias: Double,
    seedlistOutputBias: Double
  )

  case class PeerDiscoveryDelay(
    checkPeersAttemptDelay: FiniteDuration,
    checkPeersMaxDelay: FiniteDuration,
    additionalDiscoveryDelay: FiniteDuration,
    minPeers: PosInt
  )

  case class AddressesConfig(locked: Set[Address])

  case class MinMax(min: NonNegLong, max: NonNegLong)

  case class AllowSpendsConfig(lastValidEpochProgress: MinMax)

  case class TokenLocksConfig(minEpochProgressesToLock: NonNegLong)

  case class LastGlobalSnapshotsSyncConfig(syncOffset: NonNegLong, maxLastGlobalSnapshotsInMemory: PosInt)

  case class ValidationErrorStorageConfig(maxSize: PosInt)

  case class PriceOracleConfig(
    allowedMetagraphIds: Option[List[Address]],
    minEpochsBetweenUpdates: NonNegLong
  )

  object PriceOracleConfig {
    val default = PriceOracleConfig(List.empty.some, NonNegLong.MaxValue)
  }
}
