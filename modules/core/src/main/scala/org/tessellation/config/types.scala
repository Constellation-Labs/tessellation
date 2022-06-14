package org.tessellation.config

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.FiniteDuration

import org.tessellation.dag.snapshot.SnapshotOrdinal
import org.tessellation.dag.snapshot.epoch.EpochProgress
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.types._

import ciris.Secret
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Interval
import eu.timepit.refined.types.numeric._
import eu.timepit.refined.types.string.NonEmptyString
import fs2.io.file.Path

object types {
  type Percentage = Int Refined Interval.Closed[0, 100]

  case class AppConfig(
    environment: AppEnvironment,
    http: HttpConfig,
    db: DBConfig,
    gossip: GossipConfig,
    trust: TrustConfig,
    healthCheck: HealthCheckConfig,
    snapshot: SnapshotConfig,
    collateral: CollateralConfig,
    rewards: RewardsConfig
  )

  case class DBConfig(
    driver: NonEmptyString,
    url: NonEmptyString,
    user: NonEmptyString,
    password: Secret[String]
  )

  case class TrustDaemonConfig(
    interval: FiniteDuration
  )

  case class TrustConfig(
    daemon: TrustDaemonConfig
  )

  case class SnapshotConfig(
    heightInterval: NonNegLong,
    globalSnapshotPath: Path,
    timeTriggerInterval: FiniteDuration,
    inMemoryCapacity: NonNegLong
  )

  case class SoftStakingConfig(
    address: Address,
    nodes: NonNegInt,
    startingOrdinal: SnapshotOrdinal,
    softNodesPercentage: Percentage,
    testnetNodes: NonNegInt,
    testnetAddress: Address
  )

  case class DTMConfig(
    address: Address,
    monthly: Amount
  )

  case class StardustConfig(
    address: Address,
    percentage: Percentage
  )

  case class RewardsConfig(
    epochs: PosInt,
    epochDurationInYears: PosDouble,
    baseEpochReward: Amount,
    softStaking: SoftStakingConfig,
    dtm: DTMConfig,
    stardust: StardustConfig,
    rewardsPerEpoch: SortedMap[EpochProgress, Amount]
  )

  object RewardsConfig {

    val default = RewardsConfig(
      epochs = 4,
      epochDurationInYears = 2.5,
      baseEpochReward = Amount(85333333320000000L),
      softStaking = SoftStakingConfig(
        address = Address("DAG77VVVRvdZiYxZ2hCtkHz68h85ApT5b2xzdTkn"),
        nodes = 30,
        startingOrdinal = SnapshotOrdinal(0L),
        softNodesPercentage = 40,
        testnetNodes = 50,
        testnetAddress = Address("DAG0qE5tkz6cMUD5M2dkqgfV4TQCzUUdAP5MFM9P")
      ),
      dtm = DTMConfig(
        address = Address("DAG0Njmo6JZ3FhkLsipJSppepUHPuTXcSifARfvK"),
        monthly = Amount(200000000000000L)
      ),
      stardust = StardustConfig(
        address = Address("DAGSTARDUSTCOLLECTIVEHZOIPHXZUBFGNXWJETZVSPAPAHMLXS"),
        percentage = 10
      ),
      rewardsPerEpoch = SortedMap(
        EpochProgress(1296000L) -> Amount(658_43621389L),
        EpochProgress(2592000L) -> Amount(329_21810694L),
        EpochProgress(3888000L) -> Amount(164_60905347L),
        EpochProgress(5184000L) -> Amount(82_30452674L)
      )
    )
  }

}
