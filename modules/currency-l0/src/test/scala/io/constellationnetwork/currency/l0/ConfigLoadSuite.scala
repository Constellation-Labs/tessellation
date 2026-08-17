package io.constellationnetwork.currency.l0

import cats.effect.IO

import io.constellationnetwork.currency.l0.config.types.AppConfigReader
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types.SnapshotConfig
import io.constellationnetwork.node.shared.ext.pureconfig._

import eu.timepit.refined.pureconfig._
import pureconfig.ConfigSource
import pureconfig.generic.auto._
import pureconfig.module.catseffect.syntax._
import pureconfig.module.enumeratum._
import weaver.SimpleIOSuite

object ConfigLoadSuite extends SimpleIOSuite {

  private val source: ConfigSource =
    ConfigSource.resources("currency-l0.conf").withFallback(ConfigSource.default)

  test("the packaged Currency L0 config preserves distinct selector and controller caps in the join hash") {
    source.loadF[IO, AppConfigReader]().map { cfg =>
      SnapshotConfig
        .resolveEffectiveConsensusConfig(cfg.snapshot, AppEnvironment.Integrationnet)
        .fold(
          error => failure(error.getMessage),
          effective =>
            expect.same(Some(1000), effective.facilitatorSelectionMax) &&
              expect.same(Some(20), effective.maxFacilitatorCount.map(_.value)) &&
              expect.same(Some(9), effective.coreCommitteeSize) &&
              expect.same(0, effective.quorumShrinkActivationViews) &&
              expect.same(9, effective.activeAdmissionMinProbationReentrySlots) &&
              expect.same(10, effective.activeAdmissionRecentSignerWindow)
        )
    }
  }
}
