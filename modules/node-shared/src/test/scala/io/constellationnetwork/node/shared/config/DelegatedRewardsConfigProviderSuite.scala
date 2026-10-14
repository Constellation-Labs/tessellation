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

  pureTest("delaying Mainnet activation keeps era, emissions, and distribution historical past the inherited placeholder") {
    val delayedEpoch = EpochProgress(3_100_000L)
    val delayed = provider.getConfig(delayedEpoch)
    val era = delayed.eras(AppEnvironment.Mainnet)
    val emissionAt = delayed.emissionConfig(AppEnvironment.Mainnet)
    val programAt = delayed.percentDistribution(AppEnvironment.Mainnet)
    val historicalEmission = config.emissionConfig(AppEnvironment.Mainnet)(before)
    val historicalProgram = config.percentDistribution(AppEnvironment.Mainnet)(before)

    expect(!era.isActive(activation), "the original placeholder must not activate a delayed policy")
      .and(expect.same(historicalEmission, emissionAt(activation)))
      .and(expect.same(historicalProgram, programAt(activation)))
      .and(expect(!era.isActive(EpochProgress(3_099_999L)), "the preceding delayed epoch is still historical"))
      .and(expect.same(historicalEmission, emissionAt(EpochProgress(3_099_999L))))
      .and(expect.same(historicalProgram, programAt(EpochProgress(3_099_999L))))
      .and(expect(era.isActive(delayedEpoch), "the delayed epoch activates the era"))
      .and(expect.same(provider.fixedEmissionRate, emissionAt(delayedEpoch).iInitial))
      .and(expect.same(provider.fixedEmissionRate, emissionAt(delayedEpoch).iTarget))
      .and(expect.same(provider.metanomicsV2DistributionProgram, programAt(delayedEpoch)))
  }

  pureTest("an earlier Mainnet activation supplies the new program when the era first becomes active") {
    val earlierEpoch = EpochProgress(2_900_000L)
    val earlier = provider.getConfig(earlierEpoch)
    val era = earlier.eras(AppEnvironment.Mainnet)
    val emissionAt = earlier.emissionConfig(AppEnvironment.Mainnet)
    val programAt = earlier.percentDistribution(AppEnvironment.Mainnet)
    val precedingEpoch = EpochProgress(2_899_999L)
    val followingEpoch = EpochProgress(2_900_001L)

    expect(!era.isActive(precedingEpoch), "the preceding earlier epoch is still historical")
      .and(expect.same(config.emissionConfig(AppEnvironment.Mainnet)(before), emissionAt(precedingEpoch)))
      .and(expect.same(config.percentDistribution(AppEnvironment.Mainnet)(before), programAt(precedingEpoch)))
      .and(expect(era.isActive(earlierEpoch), "the earlier epoch activates the era"))
      .and(expect.same(provider.fixedEmissionRate, emissionAt(earlierEpoch).iInitial))
      .and(expect.same(provider.fixedEmissionRate, emissionAt(earlierEpoch).iTarget))
      .and(expect.same(provider.metanomicsV2DistributionProgram, programAt(earlierEpoch)))
      .and(expect(era.isActive(followingEpoch), "the following epoch remains active"))
      .and(expect.same(emissionAt(earlierEpoch), emissionAt(followingEpoch)))
      .and(expect.same(programAt(earlierEpoch), programAt(followingEpoch)))
  }

  pureTest("disabling Mainnet through the builder disables both schedules even at the maximum epoch") {
    val disabled = provider.getConfig(EpochProgress.MaxValue)
    val era = disabled.eras(AppEnvironment.Mainnet)
    val epochs = List(EpochProgress.MinValue, activation, EpochProgress.MaxValue)
    val emissions = epochs.map(disabled.emissionConfig(AppEnvironment.Mainnet))
    val programs = epochs.map(disabled.percentDistribution(AppEnvironment.Mainnet))
    val historicalEmission = config.emissionConfig(AppEnvironment.Mainnet)(before)
    val historicalProgram = config.percentDistribution(AppEnvironment.Mainnet)(before)

    expect.same(DelegatedRewardsEra.Disabled, era)
      .and(expect(epochs.forall(epoch => !era.isActive(epoch)), "the disabled sentinel must never activate the policy"))
      .and(expect(emissions.forall(_ == historicalEmission), "disabled emission settings must retain the historical rate parameters"))
      .and(expect(programs.forall(_ == historicalProgram), "disabled distributions must retain the old weights and commission policy"))
  }
}
