package org.tessellation.config

import cats.syntax.partialOrder._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.FiniteDuration

import org.tessellation.cli.AppEnvironment
import org.tessellation.config.types.RewardsConfig._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.transaction.TransactionAmount
import org.tessellation.sdk.config.types._
import org.tessellation.sdk.domain.transaction.TransactionValidator.stardustPrimary

import ciris.Secret
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric._
import eu.timepit.refined.types.string.NonEmptyString
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
    rewards: RewardsConfig,
    stateChannelPullDelay: NonNegLong,
    stateChannelPurgeDelay: NonNegLong,
    peerDiscoveryDelay: PeerDiscoveryDelay,
    proposalSelect: ProposalSelectConfig
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

  @newtype
  case class Weight(value: NonNegLong)

  case class ProgramsDistributionConfig(
    weights: Map[Address, Weight],
    remainingWeight: Weight
  )

  case class OneTimeReward(epoch: EpochProgress, address: Address, amount: TransactionAmount)

  case class RewardsConfig(
    programs: EpochProgress => ProgramsDistributionConfig = mainnetProgramsDistributionConfig,
    rewardsPerEpoch: SortedMap[EpochProgress, Amount] = mainnetRewardsPerEpoch,
    oneTimeRewards: List[OneTimeReward] = List(
      // Transferring final balance of 4,343,029,488,479,231 from DAGSTARDUSTCOLLECTIVEHZOIPHXZUBFGNXWJETZVSPAPAHMLXS
      // as of the last minting it received awards (Epoch 1352274)
      OneTimeReward(EpochProgress(1353745L), stardustNewPrimary, TransactionAmount(4_343_029_488_479_231L))
    )
  )

  object RewardsConfig {
    val stardustNewPrimary = Address("DAG8vD8BUhCpTnYXEadQVGhHjgxEZZiafbzwmKKh")
    val stardustSecondary: Address = Address("DAG8VT7bxjs1XXBAzJGYJDaeyNxuThikHeUTp9XY")
    val softStaking: Address = Address("DAG77VVVRvdZiYxZ2hCtkHz68h85ApT5b2xzdTkn")
    val testnet: Address = Address("DAG0qE5tkz6cMUD5M2dkqgfV4TQCzUUdAP5MFM9P")
    val dataPool: Address = Address("DAG3RXBWBJq1Bf38rawASakLHKYMbRhsDckaGvGu")
    val integrationNet: Address = Address("DAG8jE4CHy9T2izWFEv8K6rp5hNJq11SyLEVYnt8")

    val mainnetProgramsDistributionConfig: EpochProgress => ProgramsDistributionConfig = {
      case epoch if epoch < EpochProgress(1336392L) =>
        ProgramsDistributionConfig(
          weights = Map(
            stardustPrimary -> Weight(5L),
            stardustSecondary -> Weight(5L),
            softStaking -> Weight(20L),
            testnet -> Weight(1L),
            dataPool -> Weight(65L)
          ),
          remainingWeight = Weight(4L) // facilitators
        )
      case epoch if epoch < EpochProgress(1352274L) =>
        ProgramsDistributionConfig(
          weights = Map(
            stardustPrimary -> Weight(5L),
            stardustSecondary -> Weight(5L),
            testnet -> Weight(5L),
            dataPool -> Weight(55L)
          ),
          remainingWeight = Weight(30L) // facilitators
        )
      case _ =>
        ProgramsDistributionConfig(
          weights = Map(
            stardustNewPrimary -> Weight(5L),
            stardustSecondary -> Weight(5L),
            testnet -> Weight(3L),
            dataPool -> Weight(55L),
            integrationNet -> Weight(15L)
          ),
          remainingWeight = Weight(17L) // facilitators
        )
    }

    val mainnetRewardsPerEpoch: SortedMap[EpochProgress, Amount] = SortedMap(
      EpochProgress(1296000L) -> Amount(658_43621389L),
      EpochProgress(2592000L) -> Amount(329_21810694L),
      EpochProgress(3888000L) -> Amount(164_60905347L),
      EpochProgress(5184000L) -> Amount(82_30452674L)
    )
  }
}
