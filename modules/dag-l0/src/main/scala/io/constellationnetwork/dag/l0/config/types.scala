package io.constellationnetwork.dag.l0.config

import cats.syntax.partialOrder._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.transaction.TransactionAmount
import io.constellationnetwork.schema.{NonNegFraction, SnapshotOrdinal}

import ciris.Secret
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric._
import eu.timepit.refined.types.string.NonEmptyString
import io.estatico.newtype.macros.newtype

import types.MainnetRewardsConfig._

object types {
  case class AppConfigReader(
    trust: TrustConfig,
    snapshot: SnapshotConfig,
    stateChannel: StateChannelConfig,
    peerDiscovery: PeerDiscoveryConfig,
    incremental: IncrementalConfig
  )

  case class AppConfig(
    trust: TrustConfig,
    snapshot: SnapshotConfig,
    rewards: ClassicRewardsConfig,
    stateChannel: StateChannelConfig,
    peerDiscovery: PeerDiscoveryConfig,
    incremental: IncrementalConfig,
    shared: SharedConfig
  ) {
    val environment = shared.environment
    val gossip = shared.gossip
    val http = shared.http
    val snapshotSize = shared.snapshotSize
    val collateral = shared.collateral
  }

  case class IncrementalConfig(
    lastFullGlobalSnapshotOrdinal: Map[AppEnvironment, SnapshotOrdinal]
  )

  case class StateChannelConfig(
    pullDelay: NonNegLong,
    purgeDelay: NonNegLong
  )

  case class PeerDiscoveryConfig(
    delay: PeerDiscoveryDelay
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

  object MainnetRewardsConfig {
    val stardustPrimary: Address = Address("DAGSTARDUSTCOLLECTIVEHZOIPHXZUBFGNXWJETZVSPAPAHMLXS")
    val stardustNewPrimary: Address = Address("DAG8vD8BUhCpTnYXEadQVGhHjgxEZZiafbzwmKKh")
    val stardustSecondary: Address = Address("DAG8VT7bxjs1XXBAzJGYJDaeyNxuThikHeUTp9XY")
    val softStaking: Address = Address("DAG77VVVRvdZiYxZ2hCtkHz68h85ApT5b2xzdTkn")
    val testnet: Address = Address("DAG0qE5tkz6cMUD5M2dkqgfV4TQCzUUdAP5MFM9P")
    val dataPool: Address = Address("DAG3RXBWBJq1Bf38rawASakLHKYMbRhsDckaGvGu")
    val integrationNet: Address = Address("DAG8jE4CHy9T2izWFEv8K6rp5hNJq11SyLEVYnt8")
    val protocolWalletMetanomics: Address = Address("DAG86Joz5S7hkL8N9yqTuVs5vo1bzQLwF3MUTUMX")
    val treasureWalletMetanomics1: Address = Address("DAG3tC21XtXvoUD8hTMQzHm7T21MHahuFPVrPBtR")
    val treasureWalletMetanomics3: Address = Address("DAG1nw5WkZdQf96Df3PkrjLxeHj2EV3oLkWPZQcD")

    val mainnetProgramsDistributionConfig: EpochProgress => ProgramsDistributionConfig = {
      case epoch if epoch < EpochProgress(1336392L) =>
        ProgramsDistributionConfig(
          weights = Map(
            stardustPrimary -> NonNegFraction.unsafeFrom(5L, 100L),
            stardustSecondary -> NonNegFraction.unsafeFrom(5L, 100L),
            softStaking -> NonNegFraction.unsafeFrom(20L, 100L),
            testnet -> NonNegFraction.unsafeFrom(1L, 100L),
            dataPool -> NonNegFraction.unsafeFrom(65L, 100L)
          ),
          validatorsWeight = NonNegFraction.unsafeFrom(4L, 100L),
          delegatorsWeight = NonNegFraction.unsafeFrom(0L, 100L)
        )
      case epoch if epoch < EpochProgress(1352274L) =>
        ProgramsDistributionConfig(
          weights = Map(
            stardustPrimary -> NonNegFraction.unsafeFrom(5L, 100L),
            stardustSecondary -> NonNegFraction.unsafeFrom(5L, 100L),
            testnet -> NonNegFraction.unsafeFrom(5L, 100L),
            dataPool -> NonNegFraction.unsafeFrom(55L, 100L)
          ),
          validatorsWeight = NonNegFraction.unsafeFrom(30L, 100L),
          delegatorsWeight = NonNegFraction.unsafeFrom(0L, 100L)
        )
      case epoch if epoch < EpochProgress(1947530L) =>
        ProgramsDistributionConfig(
          weights = Map(
            stardustNewPrimary -> NonNegFraction.unsafeFrom(5L, 100L),
            stardustSecondary -> NonNegFraction.unsafeFrom(5L, 100L),
            testnet -> NonNegFraction.unsafeFrom(3L, 100L),
            dataPool -> NonNegFraction.unsafeFrom(55L, 100L),
            integrationNet -> NonNegFraction.unsafeFrom(15L, 100L)
          ),
          validatorsWeight = NonNegFraction.unsafeFrom(17L, 100L),
          delegatorsWeight = NonNegFraction.unsafeFrom(0L, 100L)
        )
      case epoch if epoch < EpochProgress(2000000L) => // TODO - change value at launch - Marks transition to new base case
        ProgramsDistributionConfig(
          weights = Map(
            stardustNewPrimary -> NonNegFraction.unsafeFrom(7L, 100L),
            testnet -> NonNegFraction.unsafeFrom(7L, 100L),
            dataPool -> NonNegFraction.unsafeFrom(42L, 100L),
            integrationNet -> NonNegFraction.unsafeFrom(20L, 100L)
          ),
          validatorsWeight = NonNegFraction.unsafeFrom(24L, 100L),
          delegatorsWeight = NonNegFraction.unsafeFrom(0L, 100L)
        )
      case _ =>
        ProgramsDistributionConfig(
          weights = Map(
            stardustNewPrimary -> NonNegFraction.unsafeFrom(5L, 100L),
            testnet -> NonNegFraction.unsafeFrom(24L, 1000L),
            integrationNet -> NonNegFraction.unsafeFrom(88L, 1000L),
            protocolWalletMetanomics -> NonNegFraction.unsafeFrom(30L, 100L)
          ),
          validatorsWeight = NonNegFraction.unsafeFrom(88L, 1000L),
          delegatorsWeight = NonNegFraction.unsafeFrom(45L, 100L)
        )
    }

    // setting an epoch progress value here will override the emission formula calculation
    val mainnetRewardsPerEpoch: SortedMap[EpochProgress, Amount] = SortedMap(
      EpochProgress(1296000L) -> Amount(658_43621389L),
      EpochProgress(2592000L) -> Amount(329_21810694L),
      EpochProgress(3888000L) -> Amount(164_60905347L),
      EpochProgress(5184000L) -> Amount(82_30452674L)
    )

    val classicMainnetRewardsConfig: ClassicRewardsConfig = ClassicRewardsConfig(
      mainnetProgramsDistributionConfig,
      mainnetRewardsPerEpoch,
      List(
        // Transferring final balance of 4,343,029,488,479,231 from DAGSTARDUSTCOLLECTIVEHZOIPHXZUBFGNXWJETZVSPAPAHMLXS
        // as of the last minting it received awards (Epoch 1352274)
        OneTimeReward(EpochProgress(1353745L), stardustNewPrimary, TransactionAmount(4_343_029_488_479_231L)),
        // One-time minting to treasure wallets according to new metanomics
        OneTimeReward(EpochProgress(1928500L), treasureWalletMetanomics1, TransactionAmount(150_000_000_000_000_00L)),
        OneTimeReward(EpochProgress(1928500L), protocolWalletMetanomics, TransactionAmount(150_000_000_000_000_00L)),
        OneTimeReward(EpochProgress(1928500L), treasureWalletMetanomics3, TransactionAmount(150_000_000_000_000_00L))
      )
    )
  }
}
