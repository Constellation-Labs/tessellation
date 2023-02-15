package org.tessellation.config

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.FiniteDuration

import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.types._

import ciris.Secret
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric._
import eu.timepit.refined.types.string.NonEmptyString
import fs2.io.file.Path
import io.estatico.newtype.macros.newtype

object types {
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
    consensus: ConsensusConfig,
    globalSnapshotPath: Path,
    inMemoryCapacity: NonNegLong
  )

  @newtype
  case class Weight(value: NonNegLong)

  case class ProgramsDistributionConfig(
    weights: Map[Address, Weight] = Map(
      Address("DAGSTARDUSTCOLLECTIVEHZOIPHXZUBFGNXWJETZVSPAPAHMLXS") -> Weight(5L), // stardust tax primary
      Address("DAG8VT7bxjs1XXBAzJGYJDaeyNxuThikHeUTp9XY") -> Weight(5L), // stardust tax secondary
      Address("DAG77VVVRvdZiYxZ2hCtkHz68h85ApT5b2xzdTkn") -> Weight(20L), // soft staking
      Address("DAG0qE5tkz6cMUD5M2dkqgfV4TQCzUUdAP5MFM9P") -> Weight(1L), // testnet
      Address("DAG0Njmo6JZ3FhkLsipJSppepUHPuTXcSifARfvK") -> Weight(65L) // data pool
    ),
    remainingWeight: Weight = Weight(4L) // facilitators
  )

  case class RewardsConfig(
    programs: ProgramsDistributionConfig = ProgramsDistributionConfig(),
    rewardsPerEpoch: SortedMap[EpochProgress, Amount] = SortedMap(
      EpochProgress(1296000L) -> Amount(658_43621389L),
      EpochProgress(2592000L) -> Amount(329_21810694L),
      EpochProgress(3888000L) -> Amount(164_60905347L),
      EpochProgress(5184000L) -> Amount(82_30452674L)
    )
  )
}
