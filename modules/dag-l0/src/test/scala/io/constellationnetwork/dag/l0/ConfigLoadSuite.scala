package io.constellationnetwork.dag.l0

import cats.effect.IO

import io.constellationnetwork.dag.l0.config.types._
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types.{SharedConfigReader, SnapshotConfig}
import io.constellationnetwork.node.shared.ext.pureconfig._

import eu.timepit.refined.pureconfig._
import pureconfig.ConfigSource
import pureconfig.generic.auto._
import pureconfig.module.catseffect.syntax._
import pureconfig.module.enumeratum._
import weaver.SimpleIOSuite

/** Smoke test that the config the dag-l0 node ships actually parses, using the exact `ConfigSource` the app builds at startup. The node
  * loads config in two levels:
  *
  *   - `TessellationIOApp.main` loads `SharedConfigReader` (the node-shared base, `application.conf`)
  *   - `Main.run` then loads `AppConfigReader` (the dag-l0 layer, `dag-l0.conf`)
  *
  * both off `TessellationIOApp.loadConfigAs`, i.e. `ConfigSource.resources("dag-l0.conf")` falling back to `ConfigSource.default`.
  * Replicating that here turns a packaged-config regression -- e.g. a refined-type field set to a value its predicate rejects, like the
  * `PosInt` quorum-shrink map that was given `0` (fix e159385fd) -- into a red test instead of a `ConfigReaderException` that crashes every
  * node at startup. See feedback_fix_type_not_revert_feature.
  */
object ConfigLoadSuite extends SimpleIOSuite {

  // Mirror of TessellationIOApp.loadConfigAs with Main.configFiles = List("dag-l0.conf"). Held in
  // sync by hand (configFiles is protected) -- it is a stable single-element list.
  private val source: ConfigSource =
    List("dag-l0.conf").foldRight(ConfigSource.default) { (file, acc) =>
      ConfigSource.resources(file).withFallback(acc)
    }

  test("level 1: SharedConfigReader parses from the packaged config (TessellationIOApp startup)") {
    source.loadF[IO, SharedConfigReader]().as(success)
  }

  test("level 2: AppConfigReader parses, and the per-env quorum-shrink map keeps its 0s (Main startup)") {
    source.loadF[IO, AppConfigReader]().map { cfg =>
      val qs = cfg.snapshot.quorumShrinkActivationViews
      // The map is typed Int precisely so 0 (= disabled) is representable per environment; a PosInt
      // typing rejects these 0s at load (the alpha.158 startup crash). 0 is the regression guard.
      expect(qs.get(AppEnvironment.Testnet).contains(10))
        .and(expect(qs.get(AppEnvironment.Mainnet).contains(0)))
        .and(expect(qs.get(AppEnvironment.Integrationnet).contains(0)))
        .and(expect(qs.get(AppEnvironment.Dev).contains(0)))
    }
  }

  test("the packaged IntegrationNet config resolves identically for the join fence and live consensus") {
    source.loadF[IO, AppConfigReader]().map { cfg =>
      val resolved = SnapshotConfig.resolveEffectiveConsensusConfig(cfg.snapshot, AppEnvironment.Integrationnet)

      resolved.fold(
        error => failure(error.getMessage),
        effective =>
          expect.same(Some(1000), effective.facilitatorSelectionMax) &&
            expect.same(Some(9), effective.coreCommitteeSize) &&
            expect.same(0, effective.quorumShrinkActivationViews) &&
            expect.same(9, effective.activeAdmissionMinProbationReentrySlots) &&
            expect.same(10, effective.activeAdmissionRecentSignerWindow) &&
            expect.same(Some(19), effective.activeFacilitatorTarget) &&
            expect.same(Some(37), effective.activeFacilitatorMax)
      )
    }
  }

  test("resolver defaults and floors are applied before hashing") {
    source.loadF[IO, AppConfigReader]().map { cfg =>
      val withoutEnvironmentOverrides = cfg.snapshot.copy(
        maxFacilitatorCount = Map.empty,
        coreCommitteeSize = Map.empty,
        quorumShrinkActivationViews = Map.empty,
        activeAdmissionMinProbationReentrySlots = Map.empty,
        activeAdmissionRecentSignerWindow = Map(AppEnvironment.Integrationnet -> 1),
        activeFacilitatorTarget = Map.empty,
        activeFacilitatorMax = Map.empty
      )
      val resolved = SnapshotConfig.resolveEffectiveConsensusConfig(withoutEnvironmentOverrides, AppEnvironment.Integrationnet)

      resolved.fold(
        error => failure(error.getMessage),
        effective =>
          expect.same(None, effective.facilitatorSelectionMax) &&
            expect.same(Some(3), effective.coreCommitteeSize) &&
            expect.same(0, effective.quorumShrinkActivationViews) &&
            expect.same(0, effective.activeAdmissionMinProbationReentrySlots) &&
            expect.same(3, effective.activeAdmissionRecentSignerWindow) &&
            expect.same(cfg.snapshot.consensus.activeFacilitatorTarget, effective.activeFacilitatorTarget) &&
            expect.same(cfg.snapshot.consensus.activeFacilitatorMax, effective.activeFacilitatorMax)
      )
    }
  }

  test("resolver rejects an invalid controller range before the node can join") {
    source.loadF[IO, AppConfigReader]().map { cfg =>
      val invalid = cfg.snapshot.copy(
        activeFacilitatorTarget = Map(AppEnvironment.Integrationnet -> 8),
        activeFacilitatorMax = Map(AppEnvironment.Integrationnet -> 7)
      )

      expect(SnapshotConfig.resolveEffectiveConsensusConfig(invalid, AppEnvironment.Integrationnet).isLeft)
    }
  }
}
