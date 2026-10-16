package io.constellationnetwork.node.shared.config

import cats.syntax.partialOrder._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.MainnetRewardsConfig._
import io.constellationnetwork.node.shared.config.types.{DelegatedRewardsConfig, _}
import io.constellationnetwork.schema.NonNegFraction
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.transaction.TransactionAmount
import io.constellationnetwork.syntax.sortedCollection.sortedMapSyntax

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}

/** Provides delegated rewards configuration.
  */
trait DelegatedRewardsConfigProvider {

  def getConfig(): DelegatedRewardsConfig
}

object DefaultDelegatedRewardsConfigProvider extends DelegatedRewardsConfigProvider {

  val wallet1: Address = Address("DAG6bg5PHo9etT9wbnbxyDbnRdv7ge12RujwfeKR")
  val wallet23: Address = Address("DAG4ARHfARnth2EW94GNVhWwwtZV6hqMkYYAEf46")
  val wallet4: Address = Address("DAG471pakWWz3ZAFvX5uhA7uyufSkv7E41Lh77Ge")
  val mintEpoch1: EpochProgress = EpochProgress(NonNegLong.unsafeFrom(2_507_521L + 11_000L)) // approximately 11/6/2025
  val initialDagPrice: NonNegFraction = NonNegFraction.unsafeFrom(25, 1) // DAG per USD ($0.04 per DAG)

  // Fixed-emission metanomics: a flat 2% per year of the DAG outstanding in the ledger, split by metanomicsV2DistributionProgram.
  val fixedEmissionRate: NonNegFraction = NonNegFraction.unsafeFrom(2, 100)
  val fixedCommission: NonNegFraction = NonNegFraction.unsafeFrom(10, 100)
  val mainnetMetanomicsV2Epoch: EpochProgress = EpochProgress(2_913_350L)
  // Registration and new delegations need 250K DAG on the node's wallet until the Nov 14 2026 collateral change, then 1M DAG.
  // ~2026-11-14 00:00 UTC at the ~60.7 s/epoch observed after activation; re-check the pace before that date.
  val mainnetRegistrationBalanceRaiseEpoch: EpochProgress = EpochProgress(2_954_500L)
  def minRegistrationBalance(epoch: EpochProgress): Amount =
    if (epoch < mainnetRegistrationBalanceRaiseEpoch) Amount(250_000_00000000L) else Amount(1_000_000_00000000L)

  private lazy val defaultConfig = getConfig()

  // The fixed-commission program also switches node registration from seedlist membership to a minRegistrationBalance
  // balance on the signing address, and lets delegators stake to any registered node.
  def metanomicsV2Active(environment: AppEnvironment, epoch: EpochProgress): Boolean =
    defaultConfig.percentDistribution.get(environment).exists(_(epoch).fixedCommission.isDefined)

  def getConfig(): DelegatedRewardsConfig = DelegatedRewardsConfig(
    flatInflationRate = NonNegFraction.unsafeFrom(3, 100), // 3% flat inflation rate
    emissionConfig = Map(
      AppEnvironment.Dev -> { _: EpochProgress =>
        EmissionConfigEntry(
          epochsPerYear = PosLong(100L),
          asOfEpoch = EpochProgress(0L),
          iTarget = NonNegFraction.unsafeFrom(5, 1000), // 0.5% target inflation
          iInitial = NonNegFraction.unsafeFrom(6, 100), // 6% initial inflation
          lambda = NonNegFraction.unsafeFrom(1, 10), // 0.1 lambda parameter
          iImpact = NonNegFraction.unsafeFrom(35, 100), // 0.35 impact factor
          totalSupply = Amount(3693588685_00000000L), // Total supply with 10^8 scaling
          dagPrices = SortedMap(
            EpochProgress(0L) -> initialDagPrice
          ),
          epochsPerMonth = NonNegLong(10L)
        )
      },
      AppEnvironment.Testnet -> {
        case epoch if epoch < EpochProgress(1049436L) => // ~ 8 jul 2025
          EmissionConfigEntry(
            epochsPerYear = PosLong(732000L),
            asOfEpoch = EpochProgress(997094L),
            iTarget = NonNegFraction.unsafeFrom(5, 1000), // 0.5% target inflation
            iInitial = NonNegFraction.unsafeFrom(6, 100), // 6% initial inflation
            lambda = NonNegFraction.unsafeFrom(1, 10), // 0.1 lambda parameter
            iImpact = NonNegFraction.unsafeFrom(35, 100), // 0.35 impact factor
            totalSupply = Amount(3693588685_00000000L), // Total supply with 10^8 scaling
            dagPrices = SortedMap(
              EpochProgress(0L) -> initialDagPrice
            ),
            epochsPerMonth = NonNegLong(84L) // one hour
          )
        case _ =>
          EmissionConfigEntry(
            epochsPerYear = PosLong(732000L),
            asOfEpoch = EpochProgress(997094L),
            iTarget = NonNegFraction.unsafeFrom(5, 1000), // 0.5% target inflation
            iInitial = NonNegFraction.unsafeFrom(6, 100), // 6% initial inflation
            lambda = NonNegFraction.unsafeFrom(1, 10), // 0.1 lambda parameter
            iImpact = NonNegFraction.unsafeFrom(35, 100), // 0.35 impact factor
            totalSupply = Amount(3693588685_00000000L), // Total supply with 10^8 scaling
            dagPrices = SortedMap(
              EpochProgress(0L) -> initialDagPrice
            ),
            epochsPerMonth = NonNegLong(61000L) // 732000 / 12
          )
      },
      AppEnvironment.Integrationnet -> { _: EpochProgress =>
        EmissionConfigEntry(
          epochsPerYear = PosLong(732000L),
          asOfEpoch = EpochProgress(751085L),
          iTarget = NonNegFraction.unsafeFrom(5, 1000), // 0.5% target inflation
          iInitial = NonNegFraction.unsafeFrom(6, 100), // 6% initial inflation
          lambda = NonNegFraction.unsafeFrom(1, 10), // 0.1 lambda parameter
          iImpact = NonNegFraction.unsafeFrom(35, 100), // 0.35 impact factor
          totalSupply = Amount(3693588685_00000000L), // Total supply with 10^8 scaling
          dagPrices = SortedMap(
            EpochProgress(0L) -> initialDagPrice
          ),
          epochsPerMonth = NonNegLong(61000L) // 732000 / 12
        )
      },
      AppEnvironment.Mainnet -> { epoch: EpochProgress =>
        EmissionConfigEntry(
          epochsPerYear = PosLong(485502L),
          asOfEpoch = EpochProgress(2311565L),
          // iTarget == iInitial makes the emission formula a constant, so from mainnetMetanomicsV2Epoch
          // the price-driven variable emission collapses to the fixed 2% rate.
          iTarget = if (epoch < mainnetMetanomicsV2Epoch) NonNegFraction.unsafeFrom(5, 1000) else fixedEmissionRate,
          iInitial = if (epoch < mainnetMetanomicsV2Epoch) NonNegFraction.unsafeFrom(6, 100) else fixedEmissionRate,
          lambda = NonNegFraction.unsafeFrom(1, 10), // 0.1 lambda parameter
          iImpact = NonNegFraction.unsafeFrom(35, 100), // 0.35 impact factor
          totalSupply = Amount(3693588685_00000000L), // Total supply with 10^8 scaling
          dagPrices = SortedMap(
            EpochProgress(0L) -> initialDagPrice
          ),
          epochsPerMonth = NonNegLong(40458L), // 485502 / 12
          ledgerSupply = epoch >= mainnetMetanomicsV2Epoch
        )
      }
    ),
    percentDistribution = Map(
      AppEnvironment.Dev -> devnetDistributionProgram,
      AppEnvironment.Testnet -> testnetDistributionProgram,
      AppEnvironment.Integrationnet -> intnetDistributionProgram,
      AppEnvironment.Mainnet -> mainnetDistributionProgram
    ),
    oneTimeRewards = Map(
      AppEnvironment.Dev -> List(),
      AppEnvironment.Testnet -> List(
        OneTimeReward(
          EpochProgress(1001105L), // approx. 2025-07-11 15:30:00 +UTC
          Address("DAG0WJjvo8s1h2Mn8g9G3HXuSTN12UGF4RzF7whp"),
          TransactionAmount(12_345_000_000_00L)
        )
      ),
      AppEnvironment.Integrationnet -> List(),
      AppEnvironment.Mainnet -> List(
        OneTimeReward(mintEpoch1, wallet1, TransactionAmount(22_088_904_680_692_84L)),
        // 3_168_965_177_964_44L + 356_719_461_924_77L = 3_525_684_639_889_21L
        OneTimeReward(mintEpoch1, wallet23, TransactionAmount(3_525_684_639_889_21L)),
        OneTimeReward(mintEpoch1, wallet4, TransactionAmount(12_190_277_806_927_26L))
      )
    ),
    priceOracleEpoch = Map(
      AppEnvironment.Dev -> EpochProgress.MinValue,
      AppEnvironment.Testnet -> EpochProgress(1001105L), // approx. 2025-07-11
      AppEnvironment.Integrationnet -> EpochProgress(835520L), // approx. 07-22-2025
      AppEnvironment.Mainnet -> EpochProgress.MaxValue
    )
  )

  private val devnetDistributionProgram: EpochProgress => ProgramsDistributionConfig =
    _ =>
      ProgramsDistributionConfig(
        weights = Map.empty,
        validatorsWeight = NonNegFraction.unsafeFrom(50L, 100L),
        delegatorsWeight = NonNegFraction.unsafeFrom(50L, 100L)
      )

  private val testnetDistributionProgram: EpochProgress => ProgramsDistributionConfig = {
    case epoch if epoch < EpochProgress(997154L) =>
      ProgramsDistributionConfig(
        weights = Map.empty,
        validatorsWeight = NonNegFraction.unsafeFrom(50L, 100L),
        delegatorsWeight = NonNegFraction.unsafeFrom(50L, 100L)
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

  private val intnetDistributionProgram: EpochProgress => ProgramsDistributionConfig =
    _ =>
      ProgramsDistributionConfig(
        weights = Map.empty,
        validatorsWeight = NonNegFraction.unsafeFrom(50L, 100L),
        delegatorsWeight = NonNegFraction.unsafeFrom(50L, 100L)
      )

  val metanomicsV2DistributionProgram: ProgramsDistributionConfig =
    ProgramsDistributionConfig(
      weights = Map(
        protocolWalletMetanomics -> NonNegFraction.unsafeFrom(30L, 100L),
        // Stardust Treasury 10% and AI Inference 15% both pay to stardustNewPrimary; one map key, so one 25% entry
        stardustNewPrimary -> NonNegFraction.unsafeFrom(25L, 100L)
      ),
      validatorsWeight = NonNegFraction.unsafeFrom(10L, 100L),
      delegatorsWeight = NonNegFraction.unsafeFrom(35L, 100L), // gross; validators take fixedCommission out of it
      flatInflationRate = Some(NonNegFraction.zero),
      fixedCommission = Some(fixedCommission)
    )

  private val mainnetDistributionProgram: EpochProgress => ProgramsDistributionConfig = {
    case epoch if epoch < mainnetMetanomicsV2Epoch =>
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
    case _ => metanomicsV2DistributionProgram
  }
}
