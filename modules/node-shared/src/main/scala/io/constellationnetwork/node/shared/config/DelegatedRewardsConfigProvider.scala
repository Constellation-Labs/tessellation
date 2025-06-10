package io.constellationnetwork.node.shared.config

import cats.syntax.partialOrder._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.MainnetRewardsConfig._
import io.constellationnetwork.node.shared.config.types.{DelegatedRewardsConfig, _}
import io.constellationnetwork.schema.NonNegFraction
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.syntax.sortedCollection.sortedMapSyntax

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}

/** Provides delegated rewards configuration.
  */
trait DelegatedRewardsConfigProvider {

  def getConfig(): DelegatedRewardsConfig
}

object DefaultDelegatedRewardsConfigProvider extends DelegatedRewardsConfigProvider {

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
            EpochProgress(0L) -> NonNegFraction.unsafeFrom(25, 1) // DAG per USD ($0.04 per DAG)
          ),
          epochsPerMonth = NonNegLong(10L)
        )
      },
      AppEnvironment.Testnet -> {
        case epoch if epoch < EpochProgress(1033000L) =>
          EmissionConfigEntry(
            epochsPerYear = PosLong(732000L),
            asOfEpoch = EpochProgress(997094L),
            iTarget = NonNegFraction.unsafeFrom(5, 1000), // 0.5% target inflation
            iInitial = NonNegFraction.unsafeFrom(6, 100), // 6% initial inflation
            lambda = NonNegFraction.unsafeFrom(1, 10), // 0.1 lambda parameter
            iImpact = NonNegFraction.unsafeFrom(35, 100), // 0.35 impact factor
            totalSupply = Amount(3693588685_00000000L), // Total supply with 10^8 scaling
            dagPrices = SortedMap(
              EpochProgress(0L) -> NonNegFraction.unsafeFrom(25, 1) // DAG per USD ($0.04 per DAG)
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
              EpochProgress(0L) -> NonNegFraction.unsafeFrom(25, 1) // DAG per USD ($0.04 per DAG)
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
            EpochProgress(0L) -> NonNegFraction.unsafeFrom(25, 1) // DAG per USD ($0.04 per DAG)
          ),
          epochsPerMonth = NonNegLong(61000L) // 732000 / 12
        )
      },
      AppEnvironment.Mainnet -> { _: EpochProgress =>
        EmissionConfigEntry(
          epochsPerYear = PosLong(485502L),
          asOfEpoch = EpochProgress(2311565L),
          iTarget = NonNegFraction.unsafeFrom(5, 1000), // 0.5% target inflation
          iInitial = NonNegFraction.unsafeFrom(6, 100), // 6% initial inflation
          lambda = NonNegFraction.unsafeFrom(1, 10), // 0.1 lambda parameter
          iImpact = NonNegFraction.unsafeFrom(35, 100), // 0.35 impact factor
          totalSupply = Amount(3693588685_00000000L), // Total supply with 10^8 scaling
          dagPrices = SortedMap(
            EpochProgress(0L) -> NonNegFraction.unsafeFrom(25, 1) // DAG per USD ($0.04 per DAG)
          ),
          epochsPerMonth = NonNegLong(40458L) // 485502 / 12
        )
      }
    ),
    percentDistribution = Map(
      AppEnvironment.Dev -> devnetDistributionProgram,
      AppEnvironment.Testnet -> testnetDistributionProgram,
      AppEnvironment.Integrationnet -> intnetDistributionProgram,
      AppEnvironment.Mainnet -> mainnetDistributionProgram
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

  private val mainnetDistributionProgram: EpochProgress => ProgramsDistributionConfig =
    _ =>
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
