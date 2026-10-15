package io.constellationnetwork.node.shared.config

import cats.syntax.all._

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.schema.NonNegFraction
import io.constellationnetwork.schema.epoch.EpochProgress

import eu.timepit.refined.types.numeric.NonNegLong
import weaver.SimpleIOSuite

object DelegatedRewardsConfigProviderSuite extends SimpleIOSuite {
  private val provider = DefaultDelegatedRewardsConfigProvider
  private val config = provider.getConfig()
  private val activation = provider.mainnetMetanomicsV2Epoch
  private val before = EpochProgress(NonNegLong.unsafeFrom(activation.value.value - 1L))

  pureTest("Mainnet preserves historical emissions, distribution and registration before activation") {
    val emission = config.emissionConfig(AppEnvironment.Mainnet)(before)
    val program = config.percentDistribution(AppEnvironment.Mainnet)(before)
    expect(emission.iTarget == NonNegFraction.unsafeFrom(5, 1000))
      .and(expect(emission.iInitial == NonNegFraction.unsafeFrom(6, 100)))
      .and(expect(!emission.ledgerSupply))
      .and(expect(program.delegatorsWeight == NonNegFraction.unsafeFrom(45, 100)))
      .and(expect(program.validatorsWeight == NonNegFraction.unsafeFrom(88, 1000)))
      .and(expect(program.fixedCommission.isEmpty))
      .and(expect(program.flatInflationRate.isEmpty))
      .and(expect(!provider.metanomicsV2Active(AppEnvironment.Mainnet, before)))
  }

  List(activation, activation.next).foreach { epoch =>
    pureTest(s"Mainnet enables refreshed 2 percent emissions and all program settings at epoch ${epoch.value.value}") {
      val emission = config.emissionConfig(AppEnvironment.Mainnet)(epoch)
      val program = config.percentDistribution(AppEnvironment.Mainnet)(epoch)
      expect(emission.iTarget == NonNegFraction.unsafeFrom(2, 100))
        .and(expect(emission.iInitial == emission.iTarget))
        .and(expect(emission.ledgerSupply))
        .and(expect(program.weights(MainnetRewardsConfig.protocolWalletMetanomics) == NonNegFraction.unsafeFrom(30, 100)))
        .and(expect.same("DAG8vD8BUhCpTnYXEadQVGhHjgxEZZiafbzwmKKh", MainnetRewardsConfig.stardustNewPrimary.value.value))
        .and(expect(program.weights(MainnetRewardsConfig.stardustNewPrimary) == NonNegFraction.unsafeFrom(25, 100)))
        .and(expect(program.weights.size == 2, "Protocol and the shared Stardust wallet are the only reserved recipients"))
        .and(
          expect(
            program.weights.values
              .map(_.toBigDecimal)
              .sum + program.validatorsWeight.toBigDecimal + program.delegatorsWeight.toBigDecimal == BigDecimal(1),
            "The shared wallet must retain both allocations so total weights remain 100%"
          )
        )
        .and(expect(program.validatorsWeight == NonNegFraction.unsafeFrom(10, 100)))
        .and(expect(program.delegatorsWeight == NonNegFraction.unsafeFrom(35, 100)))
        .and(expect(program.fixedCommission.contains(NonNegFraction.unsafeFrom(10, 100))))
        .and(expect(program.flatInflationRate.contains(NonNegFraction.zero)))
        .and(expect(provider.metanomicsV2Active(AppEnvironment.Mainnet, epoch)))
        .and(expect(provider.minRegistrationBalance.value.value == 100000000000000L))
    }
  }

  List(AppEnvironment.Dev, AppEnvironment.Testnet, AppEnvironment.Integrationnet).foreach { environment =>
    pureTest(s"$environment retains existing emission and registration policy after the Mainnet boundary") {
      val emission = config.emissionConfig(environment)(activation.next)
      val program = config.percentDistribution(environment)(activation.next)
      expect(!emission.ledgerSupply)
        .and(expect(program.fixedCommission.isEmpty))
        .and(expect(program.flatInflationRate.isEmpty))
        .and(expect(!provider.metanomicsV2Active(environment, activation.next)))
    }
  }
}
