package io.constellationnetwork.dag.l0.config

import scala.collection.immutable.SortedMap

import io.constellationnetwork.dag.l0.config.types.MainnetRewardsConfig
import io.constellationnetwork.dag.l0.config.types.MainnetRewardsConfig._
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types._
import io.constellationnetwork.schema.NonNegFraction
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.epoch.EpochProgress

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
      AppEnvironment.Dev -> EmissionConfigEntry(
        epochsPerYear = PosLong(100L),
        asOfEpoch = EpochProgress(0L),
        iTarget = NonNegFraction.unsafeFrom(5, 1000), // 0.5% target inflation
        iInitial = NonNegFraction.unsafeFrom(6, 100), // 6% initial inflation
        lambda = NonNegFraction.unsafeFrom(1, 10), // 0.1 lambda parameter
        iImpact = NonNegFraction.unsafeFrom(35, 100), // 0.35 impact factor
        totalSupply = Amount(3693588685_00000000L), // Total supply with 10^8 scaling
        dagPrices = SortedMap(
          // DAG per USD format (higher number = lower DAG price)
          EpochProgress(0L) -> NonNegFraction.unsafeFrom(45, 1) // 45 DAG per USD ($0.022 per DAG)
        )
      ),
      AppEnvironment.Testnet -> EmissionConfigEntry(
        epochsPerYear = PosLong(732000L),
        asOfEpoch = EpochProgress(5000000L),
        iTarget = NonNegFraction.unsafeFrom(5, 1000), // 0.5% target inflation
        iInitial = NonNegFraction.unsafeFrom(6, 100), // 6% initial inflation
        lambda = NonNegFraction.unsafeFrom(1, 10), // 0.1 lambda parameter
        iImpact = NonNegFraction.unsafeFrom(35, 100), // 0.35 impact factor
        totalSupply = Amount(3693588685_00000000L), // Total supply with 10^8 scaling
        dagPrices = SortedMap(
          // DAG per USD format (higher number = lower DAG price)
          EpochProgress(5000000L) -> NonNegFraction.unsafeFrom(45, 1), // 45 DAG per USD ($0.022 per DAG)
          EpochProgress(5100000L) -> NonNegFraction.unsafeFrom(40, 1) // 40 DAG per USD ($0.025 per DAG)
        )
      ),
      AppEnvironment.Integrationnet -> EmissionConfigEntry(
        epochsPerYear = PosLong(732000L),
        asOfEpoch = EpochProgress(752475L),
        iTarget = NonNegFraction.unsafeFrom(5, 1000), // 0.5% target inflation
        iInitial = NonNegFraction.unsafeFrom(6, 100), // 6% initial inflation
        lambda = NonNegFraction.unsafeFrom(1, 10), // 0.1 lambda parameter
        iImpact = NonNegFraction.unsafeFrom(35, 100), // 0.35 impact factor
        totalSupply = Amount(3693588685_00000000L), // Total supply with 10^8 scaling
        dagPrices = SortedMap(
          // DAG per USD format (higher number = lower DAG price)
          EpochProgress(5000000L) -> NonNegFraction.unsafeFrom(45, 1), // 45 DAG per USD ($0.022 per DAG)
          EpochProgress(5100000L) -> NonNegFraction.unsafeFrom(40, 1) // 40 DAG per USD ($0.025 per DAG)
        )
      ),
      AppEnvironment.Mainnet -> EmissionConfigEntry(
        epochsPerYear = PosLong(732000L),
        asOfEpoch = EpochProgress(5000000L),
        iTarget = NonNegFraction.unsafeFrom(5, 1000), // 0.5% target inflation
        iInitial = NonNegFraction.unsafeFrom(6, 100), // 6% initial inflation
        lambda = NonNegFraction.unsafeFrom(1, 10), // 0.1 lambda parameter
        iImpact = NonNegFraction.unsafeFrom(35, 100), // 0.35 impact factor
        totalSupply = Amount(3693588685_00000000L), // Total supply with 10^8 scaling
        dagPrices = SortedMap(
          // DAG per USD format (higher number = lower DAG price)
          EpochProgress(5000000L) -> NonNegFraction.unsafeFrom(45, 1), // 45 DAG per USD ($0.022 per DAG)
          EpochProgress(5100000L) -> NonNegFraction.unsafeFrom(40, 1) // 40 DAG per USD ($0.025 per DAG)
        )
      )
    ),
    percentDistribution = Map(
      AppEnvironment.Dev -> ProgramsDistributionConfig(
        weights = Map.empty,
        validatorsWeight = NonNegFraction.unsafeFrom(50L, 100L),
        delegatorsWeight = NonNegFraction.unsafeFrom(50L, 100L)
      ),
      AppEnvironment.Testnet -> ProgramsDistributionConfig(
        weights = Map.empty,
        validatorsWeight = NonNegFraction.unsafeFrom(50L, 100L),
        delegatorsWeight = NonNegFraction.unsafeFrom(50L, 100L)
      ),
      AppEnvironment.Integrationnet -> ProgramsDistributionConfig(
        weights = Map.empty,
        validatorsWeight = NonNegFraction.unsafeFrom(50L, 100L),
        delegatorsWeight = NonNegFraction.unsafeFrom(50L, 100L)
      ),
      AppEnvironment.Mainnet -> ProgramsDistributionConfig(
        weights = Map(
          stardustNewPrimary -> NonNegFraction.unsafeFrom(5L, 100L),
          testnet -> NonNegFraction.unsafeFrom(24L, 1000L),
          integrationNet -> NonNegFraction.unsafeFrom(88L, 1000L),
          protocolWalletMetanomics -> NonNegFraction.unsafeFrom(30L, 100L)
        ),
        validatorsWeight = NonNegFraction.unsafeFrom(88L, 1000L),
        delegatorsWeight = NonNegFraction.unsafeFrom(45L, 100L)
      )
    )
  )
}
