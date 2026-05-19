package io.constellationnetwork.node.shared.config

import cats.data.NonEmptySet
import cats.syntax.option._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.domain.statechannel.FeeCalculatorConfig
import io.constellationnetwork.node.shared.infrastructure.selfhealth.SelfHealthHint
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
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
    setSumFix: Map[AppEnvironment, SnapshotOrdinal]
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
    signatureGracePeriod: FiniteDuration = FiniteDuration(500, "ms"),
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
    // v19 phase 2 view-from-time anchor: divisor for deriving an initial view number from
    // wall-clock progress since the parent snapshot's `consensusEndTime`. Pairs with the
    // producer side (`Facility.proposerClockMs` + `ConsensusEndTime` median + `recentRoundEndTimes`
    // window). At round start, each peer computes
    //   timeView = (local_now - parent.consensusEndTime) / viewInterval
    // and the round seeds `max(priorAbandonmentCount, timeView)` as its initial `viewNumber`.
    // NTP skew across honest nodes is +/- 10ms on AWS-class infra; with a 30s default the boundary
    // resolution is 3 parts in 10,000 -- well below view-transition granularity. Consensus-critical:
    // included in `deterministicConfigHash` so honest peers with the same parent + same `now`
    // compute the same timeView. See `docs/consensus/view-from-time-anchor.md`.
    viewInterval: FiniteDuration = 30.seconds,
    // Active-set tightening parameters. The committee for round N+1 is narrowed to
    // peers who signed at least `minParticipationInWindow` of the last
    // `tighteningWindow` successful outcomes (plus grace candidates: peers in
    // `genuinelyNewCandidates` / `deferredByCountdown`). If the surviving candidate
    // set is below `activeFacilitatorFloor`, the filter is bypassed and the full
    // eligibleFacilitators is used; BFT safety requires N >= 3f+1, so floor=4
    // prevents dropping below f=1 tolerance. Default K=10, M=6, floor=4: a peer
    // needs to sign 60% of the last K outcomes to stay in the committee. Same
    // window size as `recentProofSizes` so the two are pinned to the same horizon.
    // Consensus-critical: in `deterministicConfigHash` so honest nodes compute
    // identical committees.
    tighteningWindow: Int = 10,
    minParticipationInWindow: Int = 6,
    activeFacilitatorFloor: Int = 4,
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
    consensusSchemaVersion: Int = 19,
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
    consensusDispatcherThreads: Int = 2
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
      *   - `tighteningWindow`, `minParticipationInWindow`, `activeFacilitatorFloor`: active-set narrowing parameters; control which peers
      *     are committee members for the next round (recent signer history + grace candidates with floor fallback for cluster-wide outages)
      *   - `bootstrapDeclarationTimeoutMultiplier`: affects phase-transition timing during bootstrap
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
          s"chronicReinstatementInterval=$chronicReinstatementInterval," +
          s"lockOnVoteProtocolVersion=$lockOnVoteProtocolVersion," +
          s"bootstrapCompleteProofsThreshold=$bootstrapCompleteProofsThreshold," +
          s"bootstrapDeclarationTimeoutMultiplier=$bootstrapDeclarationTimeoutMultiplier," +
          // v7 (codex turn 2 fix): qualityDecayThreshold mutates consensus-agreed peerQuality
          // (advancer:330 decay path); must be in the hash so divergent operator values can't
          // produce silently-divergent peerQuality maps.
          s"qualityDecayThreshold=$qualityDecayThreshold," +
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
    perIpBandwidthRetryAfterSeconds: Long = 5,
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
    // Core committees and silently fork. The jar hash already gates peer connections,
    // and this field follows the same precedent as `maxFacilitatorCount` (env-keyed
    // PosInt, NOT in `deterministicConfigHash`).
    coreCommitteeSize: Map[AppEnvironment, PosInt] = Map.empty,
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
  // are bounded at the server. Default 100 is a coarse safety ceiling, not a sizing knob;
  // workload-shaping should still be done via the per-route caps. CLI/env override allowed
  // (see e.g. dag-l0/cli/http.scala publicMaxConnectionsOpts) for environments that need a
  // different ceiling.
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
    cliHttp: HttpServerConfig
  )

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
