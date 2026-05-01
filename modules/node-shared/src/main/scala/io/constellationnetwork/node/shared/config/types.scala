package io.constellationnetwork.node.shared.config

import cats.data.NonEmptySet
import cats.syntax.option._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.domain.statechannel.FeeCalculatorConfig
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
    snapshotServingConfig: Option[SnapshotServingConfig] = None
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
    maxOrdinalsPerRequest: Option[PosInt] = None
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
    minParticipationRatio: Double = 0.5,
    // v8 (2026-04-29) minimum-history floor for chronic classification. Codex-recommended
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
    // refuses to lead, immediately self-deferring into a view change if elected. Codex review
    // 2026-04-27: a peer that just finished recovery has a freshly initialized consensus storage
    // and gossip mesh; if it's elected leader of the next round, it can't propose in time and
    // wedges the round for the full proposal-phase timeout (98s observed in 2026-04-27 E2E).
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
    // Why this exists: alpha.40 testnet 2026-04-27, all three internal nodes detected the same
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
    //   - 7 (2026-04-28): v7 wire-format additions (observedResponders on Proposal +
    //     CollectingProposals; qualityDecayThreshold pulled into the hash as a latent fix).
    //   - 8 (2026-04-29): v8 chronic-classification minimum-history floor
    //     (`minObservationHistoryFloor`); changes the agreed chronicNonSigners set.
    //   - 9 (2026-04-29): v9 B1/B2 cert witness-pool widened from committee to
    //     `state.eligibleFacilitators - target`. v8 nodes would reject certs that v9 nodes
    //     accept (the eligible-but-non-committee signers), so cluster-wide cold restart is
    //     required. Fixes the apr29 wedge where 7-9 valid eviction votes were rejected as
    //     `voter_not_in_committee` when only 4 came from the 9-member committee.
    //   - 10 (2026-04-30): two bundled changes — (a) BFT-correct eviction cap at
    //     StallDetector.selectEvictionTargets (ceil(n/3) → committee.size - minQuorum,
    //     equals f for n=3f+1); (b) testnet `min-observation-history-floor` lowered 30 → 10
    //     so the chronic-non-signer classifier kicks in within ~10 rounds instead of ~30.
    //     Combined fix for the apr30 post-restart deadlock where 5+ silent FACILITY_FOREVER
    //     peers stayed in the 9-member committee, blocking 7-of-9 quorum on every round.
    //     v9 nodes have a different floor and cap arithmetic, so cluster-wide cold restart
    //     required.
    //   - 11 (2026-04-30): kick-fast leader graduation. Adds `completed >= 1` to the
    //     leader-eligibility filter at GlobalSnapshotConsensusStateCreator (and currency-l0
    //     mirror). Pre-v11 the filter only checked `participated >= minObservations`, letting
    //     chronic-flaky peers with high participated counts but zero completed rounds keep
    //     getting elected leader → no proposal → indefinite stall (the apr30 18:00 UTC
    //     wedge: 890a641e and c96c3a41 stuck round 3110992 for 36+ minutes). Under the
    //     operator's "kick-fast, recover-slow" policy, peers must demonstrate at least one
    //     completed round before they can lead. Recovery: complete one round as follower →
    //     re-enter lead-eligible pool. v10 nodes select different leaders, so cluster-wide
    //     cold restart required.
    consensusSchemaVersion: Int = 11
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
      *   - `minObservationHistoryFloor`: v8 (2026-04-29) minimum participated count before chronic classification can fire
      *   - `bootstrapDeclarationTimeoutMultiplier`: affects phase-transition timing during bootstrap
      *
      * '''Non-critical fields''' (excluded — affect timing/performance, not deterministic outcomes):
      *   - `timeTriggerInterval`, `declarationTimeout`, `lockDuration`, `reStallTimeout`, `noProgressTimeout`: timing only
      *   - `facilitiesTimeoutMultiplier`, `proposalsTimeoutMultiplier`, `signaturesTimeoutMultiplier`: timing multipliers only
      *   - `maxRoundDuration`: safety net, not consensus logic
      *   - `declarationRangeLimit`, `eventCutter`: event filtering, not consensus decisions
      *
      * '''v7 (2026-04-28) — qualityDecayThreshold reclassified.''' Previously documented as local-only and excluded from this hash. In
      * reality it mutates the consensus-agreed `lastOutcome.peerQuality` field at advancer:330 (decay halves both completed and
      * participated counters when any peer's participated exceeds threshold). Two nodes with different threshold values would compute
      * different `peerQuality` after any decay event, producing latent divergence. Now included in the hash. Pre-existing latent bug fix
      * bundled with v7's schema bump.
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
          // v8 (2026-04-29): chronic-classification floor. Changes the agreed chronicNonSigners
          // set; divergent operator values would produce silently-divergent committee composition.
          s"minObservationHistoryFloor=$minObservationHistoryFloor," +
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
    // pulls one 72 MB combined snapshot per round (~22-50s/round) ≈ 1.5 MiB/s, so this
    // gives ~3-4× headroom for legitimate parallel reads while throttling bulk pollers.
    //
    // See PerIpBandwidthLimitMiddleware for mechanism + caveats. v9 (2026-04-29) addition.
    perIpMaxBytesPerWindow: Long = 0L,
    perIpBandwidthRetryAfterSeconds: Long = 5
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

  case class HttpServerConfig(
    host: Host,
    port: Port,
    shutdownTimeout: FiniteDuration,
    maxConnections: Option[Int] = None
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
