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
    forkInfoStorage: ForkInfoStorageConfig,
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
    forkInfoStorage: ForkInfoStorageConfig,
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
    facilitiesTimeoutMultiplier: Double = 0.75,
    proposalsTimeoutMultiplier: Double = 1.5,
    signaturesTimeoutMultiplier: Double = 0.75,
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
    maxRemovalPenaltyRounds: Int = 10000
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
      *   - `quorumThresholdFraction`: determines how many declarations needed to advance phases
      *   - `exponentialPenaltyBase`: base for scaling removalPenaltyRounds per repeat eviction
      *   - `maxRemovalPenaltyRounds`: cap on total penalty so it doesn't overflow Int
      *
      * '''Non-critical fields''' (excluded — affect timing/performance, not deterministic outcomes):
      *   - `timeTriggerInterval`, `declarationTimeout`, `lockDuration`, `reStallTimeout`, `noProgressTimeout`: timing only
      *   - `facilitiesTimeoutMultiplier`, `proposalsTimeoutMultiplier`, `signaturesTimeoutMultiplier`: timing multipliers only
      *   - `maxRoundDuration`: safety net, not consensus logic
      *   - `declarationRangeLimit`, `eventCutter`: event filtering, not consensus decisions
      *   - `qualityDecayThreshold`: local peer quality tracking, no consensus effect
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
          s"quorumThresholdFraction=$quorumThresholdFraction," +
          s"exponentialPenaltyBase=$exponentialPenaltyBase," +
          s"maxRemovalPenaltyRounds=$maxRemovalPenaltyRounds"
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
    retryAfterSeconds: Long = 2
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

  case class ForkInfoStorageConfig(
    maxSize: PosInt
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
