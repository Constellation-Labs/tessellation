package io.constellationnetwork.node.shared.config

import cats.effect.IO

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types.FieldsAddedOrdinals
import io.constellationnetwork.node.shared.ext.pureconfig._

import pureconfig.ConfigSource
import weaver.SimpleIOSuite

object FieldsAddedOrdinalsSuite extends SimpleIOSuite {

  test("loads the delegated stake withdrawal fix ordinals from application config") {
    val result = ConfigSource.default
      .at("fields-added-ordinals")
      .load[FieldsAddedOrdinals]
      .map(_.fixingDelegatedStakeDoubleWithdrawal)

    IO(expect(result.exists(_.keySet == AppEnvironment.values.toSet)))
  }
}
