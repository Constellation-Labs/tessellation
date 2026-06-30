package io.constellationnetwork.dag.l0

import cats.effect.IO

import io.constellationnetwork.dag.l0.config.types._
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types.SharedConfigReader
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
}
