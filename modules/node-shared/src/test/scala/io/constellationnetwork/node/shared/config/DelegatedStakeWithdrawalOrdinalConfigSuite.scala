package io.constellationnetwork.node.shared.config

import cats.effect.IO

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types.FieldsAddedOrdinals
import io.constellationnetwork.node.shared.ext.pureconfig._
import io.constellationnetwork.schema.SnapshotOrdinal

import pureconfig.ConfigSource
import pureconfig.generic.auto._
import weaver.SimpleIOSuite

object DelegatedStakeWithdrawalOrdinalConfigSuite extends SimpleIOSuite {

  test("enables delegated stake withdrawal hardening only in dev until public activation is coordinated") {
    val loaded = ConfigSource.default.at("fields-added-ordinals").load[FieldsAddedOrdinals]

    IO(
      expect.all(
        loaded.exists(!_.fixingDelegatedStakeDoubleWithdrawal.contains(AppEnvironment.Mainnet)),
        loaded.exists(!_.fixingDelegatedStakeDoubleWithdrawal.contains(AppEnvironment.Testnet)),
        loaded.exists(!_.fixingDelegatedStakeDoubleWithdrawal.contains(AppEnvironment.Integrationnet)),
        loaded.exists(_.fixingDelegatedStakeDoubleWithdrawal.get(AppEnvironment.Dev).contains(SnapshotOrdinal.MinValue))
      )
    )
  }
}
