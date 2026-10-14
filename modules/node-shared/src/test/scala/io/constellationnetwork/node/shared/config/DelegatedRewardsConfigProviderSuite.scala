package io.constellationnetwork.node.shared.config

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.MainnetRewardsConfig._
import io.constellationnetwork.node.shared.config.types.ProgramsDistributionConfig
import io.constellationnetwork.schema.NonNegFraction
import io.constellationnetwork.schema.epoch.EpochProgress

import eu.timepit.refined.auto._
import weaver.SimpleIOSuite

object DelegatedRewardsConfigProviderSuite extends SimpleIOSuite {
  private val provider = DefaultDelegatedRewardsConfigProvider
  private val config = provider.getConfig()
  private val before = EpochProgress(2_989_999L)
  private val activation = EpochProgress(2_990_000L)
  private val after = EpochProgress(2_990_001L)

  pureTest("Mainnet inherits the proposed epoch without inventing a validator membership list") {
    val era = config.eras(AppEnvironment.Mainnet)
    expect.same(activation, provider.mainnetMetanomicsV2Epoch)
      .and(expect.same(activation, era.activationEpoch))
      .and(expect(!era.isActive(before), "the preceding epoch retains legacy rules"))
      .and(expect(era.isActive(activation), "the proposed epoch is the first active epoch"))
      .and(expect(era.isActive(after), "the following epoch keeps the new rules"))
      .and(expect(era.rewardValidators.isEmpty, "final validator membership is still unspecified"))
  }

  pureTest("the Mainnet distribution preserves historical weights and switches all new economics together") {
    val historical = ProgramsDistributionConfig(
      weights = Map(
        stardustNewPrimary -> NonNegFraction.unsafeFrom(5, 100),
        testnet -> NonNegFraction.unsafeFrom(24, 1000),
        integrationNet -> NonNegFraction.unsafeFrom(88, 1000),
        protocolWalletMetanomics -> NonNegFraction.unsafeFrom(30, 100)
      ),
      validatorsWeight = NonNegFraction.unsafeFrom(88, 1000),
      delegatorsWeight = NonNegFraction.unsafeFrom(45, 100)
    )
    val newProgram = ProgramsDistributionConfig(
      weights = Map(
        protocolWalletMetanomics -> NonNegFraction.unsafeFrom(30, 100),
        provider.stardustTreasury -> NonNegFraction.unsafeFrom(10, 100),
        provider.aiInference -> NonNegFraction.unsafeFrom(15, 100)
      ),
      validatorsWeight = NonNegFraction.unsafeFrom(10, 100),
      delegatorsWeight = NonNegFraction.unsafeFrom(35, 100),
      flatInflationRate = Some(NonNegFraction.zero),
      fixedCommission = Some(NonNegFraction.unsafeFrom(10, 100))
    )
    val programAt = config.percentDistribution(AppEnvironment.Mainnet)
    expect.same(historical, programAt(before))
      .and(expect.same(newProgram, programAt(activation)))
      .and(expect.same(newProgram, programAt(after)))
      .and(expect.same(historical, programAt(before)))
      .and(expect.same(NonNegFraction.unsafeFrom(3, 100), config.flatInflationRate))
  }

  pureTest("the Mainnet rate changes to fixed two percent without rewriting historical emission parameters") {
    val emissionAt = config.emissionConfig(AppEnvironment.Mainnet)
    val historical = emissionAt(before)
    val active = emissionAt(activation)
    expect.same(NonNegFraction.unsafeFrom(5, 1000), historical.iTarget)
      .and(expect.same(NonNegFraction.unsafeFrom(6, 100), historical.iInitial))
      .and(expect.same(NonNegFraction.unsafeFrom(2, 100), active.iTarget))
      .and(expect.same(active.iTarget, active.iInitial))
      .and(expect.same(historical.copy(iTarget = active.iTarget, iInitial = active.iInitial), active))
      .and(expect.same(active, emissionAt(after)))
      .and(expect.same(historical, emissionAt(before)))
  }

  pureTest("other environments retain disabled era policies and historical distribution behavior") {
    val environments = List(AppEnvironment.Dev, AppEnvironment.Testnet, AppEnvironment.Integrationnet)
    val policies = environments.map(environment => config.eras.getOrElse(environment, DelegatedRewardsEra.Disabled))
    val programs = environments.map(environment => config.percentDistribution(environment)(activation))
    expect(policies.forall(_ == DelegatedRewardsEra.Disabled), "the Mainnet placeholder must not activate another network")
      .and(expect(policies.forall(!_.isActive(EpochProgress.MaxValue)), "missing policies remain disabled even at the sentinel"))
      .and(expect(programs.forall(_.flatInflationRate.isEmpty), "legacy flat emissions still use the historical configuration"))
      .and(expect(programs.forall(_.fixedCommission.isEmpty), "legacy commission validation remains network-specific"))
  }
}
