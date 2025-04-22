package io.constellationnetwork.node.shared.config

import cats.data.NonEmptySet

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types.DelegatedRewardsConfig
import io.constellationnetwork.node.shared.domain.statechannel.FeeCalculatorConfig
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node.{NodeState, RewardFraction}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.transaction.TransactionAmount
import io.constellationnetwork.schema.{NonNegFraction, SnapshotOrdinal}

import com.comcast.ip4s.{Host, Port}
import derevo.cats.eqv
import derevo.derive
import eu.timepit.refined.types.numeric._
import fs2.io.file.Path

object types {

  case class FieldsAddedOrdinals(
    tessellation3Migration: Map[AppEnvironment, SnapshotOrdinal]
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
    addresses: AddressesConfig,
    allowSpends: AllowSpendsConfig,
    tokenLocks: TokenLocksConfig,
    lastGlobalSnapshotsSync: LastGlobalSnapshotsSyncConfig,
    validationErrorStorage: ValidationErrorStorageConfig,
    delegatedStaking: DelegatedStakingConfig,
    fieldsAddedOrdinals: FieldsAddedOrdinals
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
    addresses: AddressesConfig,
    allowSpends: AllowSpendsConfig,
    tokenLocks: TokenLocksConfig,
    lastGlobalSnapshotsSync: LastGlobalSnapshotsSyncConfig,
    validationErrorStorage: ValidationErrorStorageConfig,
    delegatedStaking: DelegatedStakingConfig,
    fieldsAddedOrdinals: FieldsAddedOrdinals
  )

  case class SharedTrustConfig(
    storage: TrustStorageConfig
  )

  case class SharedSnapshotConfig(
    size: SnapshotSizeConfig
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
    maxConcurrentRounds: PosInt
  )

  case class GossipConfig(
    storage: RumorStorageConfig,
    daemon: GossipDaemonConfig
  )

  case class ConsensusConfig(
    timeTriggerInterval: FiniteDuration,
    declarationTimeout: FiniteDuration,
    declarationRangeLimit: NonNegLong,
    lockDuration: FiniteDuration,
    eventCutter: EventCutterConfig
  )

  case class EventCutterConfig(
    maxBinarySizeBytes: PosInt,
    maxUpdateNodeParametersSize: PosInt
  )

  case class SnapshotConfig(
    consensus: ConsensusConfig,
    inMemoryCapacity: NonNegLong,
    snapshotPath: Path,
    snapshotInfoPath: Path,
    incrementalTmpSnapshotPath: Path,
    incrementalPersistedSnapshotPath: Path
  )

  case class HttpClientConfig(
    timeout: FiniteDuration,
    idleTimeInPool: FiniteDuration
  )

  case class HttpServerConfig(
    host: Host,
    port: Port,
    shutdownTimeout: FiniteDuration
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
    dagPrices: Map[EpochProgress, NonNegFraction]
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
    emissionConfig: Map[AppEnvironment, EmissionConfigEntry],
    percentDistribution: Map[AppEnvironment, ProgramsDistributionConfig]
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

  case class LastGlobalSnapshotsSyncConfig(syncOffset: NonNegLong, minGlobalSnapshotsToParticipateConsensus: PosInt)

  case class ValidationErrorStorageConfig(maxSize: PosInt)
}
