package io.constellationnetwork.dag.l0

import cats.effect.IO

import io.constellationnetwork.dag.l0.config.types._
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types.{SharedConfigReader, SnapshotConfig}
import io.constellationnetwork.node.shared.ext.pureconfig._

import eu.timepit.refined.pureconfig._
import eu.timepit.refined.types.numeric.PosInt
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
  * Replicating that here turns a packaged-config regression -- e.g. a refined-type field set to a value its predicate rejects -- into a red
  * test instead of a `ConfigReaderException` that crashes every node at startup.
  */
object ConfigLoadSuite extends SimpleIOSuite {

  // Mirror of TessellationIOApp.loadConfigAs with Main.configFiles = List("dag-l0.conf"). Held in
  // sync by hand (configFiles is protected) -- it is a stable single-element list.
  private val source =
    List("dag-l0.conf").foldRight(ConfigSource.default) { (file, acc) =>
      ConfigSource.resources(file).withFallback(acc)
    }

  test("level 1: SharedConfigReader parses from the packaged config (TessellationIOApp startup)") {
    source.loadF[IO, SharedConfigReader]().as(success)
  }

  test("level 2: AppConfigReader parses (Main startup)") {
    source.loadF[IO, AppConfigReader]().as(success)
  }

  test("the packaged IntegrationNet config resolves identically for the join fence and live consensus") {
    source.loadF[IO, AppConfigReader]().map { cfg =>
      val resolved = SnapshotConfig.resolveEffectiveConsensusConfig(cfg.snapshot, AppEnvironment.Integrationnet)

      resolved.fold(
        error => failure(error.getMessage),
        effective =>
          expect.same(Some(1000), effective.facilitatorSelectionMax) &&
            expect.same(0L, effective.certifiedConsensusActivationKey) &&
            expect.same(9, effective.eventTriggerThreshold)
      )
    }
  }

  test("resolver defaults are applied before hashing") {
    source.loadF[IO, AppConfigReader]().map { cfg =>
      val withoutEnvironmentOverrides = cfg.snapshot.copy(
        consensus = cfg.snapshot.consensus.copy(maxFacilitatorCount = None),
        certifiedConsensusActivationOrdinal = Map.empty
      )
      val resolved = SnapshotConfig.resolveEffectiveConsensusConfig(withoutEnvironmentOverrides, AppEnvironment.Integrationnet)

      resolved.fold(
        error => failure(error.getMessage),
        effective =>
          expect.same(None, effective.facilitatorSelectionMax) &&
            expect.same(Long.MaxValue, effective.certifiedConsensusActivationKey)
      )
    }
  }

  test("min-committee-size resolves per environment and is folded into the consensus hash") {
    source.loadF[IO, AppConfigReader]().map { cfg =>
      val resolved = AppEnvironment.values.toList.map { environment =>
        environment -> SnapshotConfig.resolveEffectiveConsensusConfig(cfg.snapshot, environment).toOption.map(_.minCommitteeSize)
      }.toMap
      val testnet = SnapshotConfig.resolveEffectiveConsensusConfig(cfg.snapshot, AppEnvironment.Testnet)
      val larger = SnapshotConfig.resolveEffectiveConsensusConfig(
        cfg.snapshot.copy(minCommitteeSize = cfg.snapshot.minCommitteeSize.updated(AppEnvironment.Testnet, PosInt(5))),
        AppEnvironment.Testnet
      )

      expect.same(
        Map[AppEnvironment, Option[Int]](
          AppEnvironment.Mainnet -> Some(10),
          AppEnvironment.Testnet -> Some(4),
          AppEnvironment.Integrationnet -> Some(4),
          AppEnvironment.Dev -> Some(3)
        ),
        resolved
      ) &&
      expect(testnet.toOption.map(_.deterministicConfigHash) != larger.toOption.map(_.deterministicConfigHash))
    }
  }

  test("every packaged network keeps the 2048-ordinal clearing interval") {
    source.loadF[IO, AppConfigReader]().map { cfg =>
      AppEnvironment.values.toList.foldLeft(success) { (acc, environment) =>
        acc && expect.same(
          Some(2048L),
          SnapshotConfig.resolveEffectiveConsensusConfig(cfg.snapshot, environment).toOption.map(_.clearingIntervalOrdinals)
        )
      }
    }
  }

  test("min-committee-size below three is rejected and absence resolves to three") {
    source.loadF[IO, AppConfigReader]().map { cfg =>
      val tooSmall = cfg.snapshot.copy(minCommitteeSize = Map(AppEnvironment.Testnet -> PosInt(2)))
      val absent = cfg.snapshot.copy(minCommitteeSize = Map.empty)

      expect(SnapshotConfig.resolveEffectiveConsensusConfig(tooSmall, AppEnvironment.Testnet).isLeft) &&
      expect.same(Some(3), SnapshotConfig.resolveEffectiveConsensusConfig(absent, AppEnvironment.Testnet).toOption.map(_.minCommitteeSize))
    }
  }

  test("certified consensus is active from genesis off mainnet and pinned to a placeholder on mainnet") {
    source.loadF[IO, AppConfigReader]().map { cfg =>
      val activation = cfg.snapshot.certifiedConsensusActivationOrdinal.view.mapValues(_.value.value).toMap

      expect.same(
        Map(
          AppEnvironment.Mainnet -> 9999999L,
          AppEnvironment.Testnet -> 0L,
          AppEnvironment.Integrationnet -> 0L,
          AppEnvironment.Dev -> 0L
        ),
        activation
      )
    }
  }

  AppEnvironment.values.foreach { environment =>
    test(s"${environment.entryName}: the named L0 config retains all shared activation values") {
      for {
        shared <- source.loadF[IO, SharedConfigReader]()
        defaults <- ConfigSource.resources("application.conf").withFallback(source).loadF[IO, SharedConfigReader]()
        app <- source.loadF[IO, AppConfigReader]()
      } yield
        SnapshotConfig
          .resolveEffectiveConsensusConfig(app.snapshot, environment)
          .fold(
            error => failure(error.getMessage),
            resolved => {
              val effective = resolved.withSharedConfig(shared, environment)
              expect.same(Some(defaults.ordinalConfigHashFor(environment)), effective.ordinalConfigHash) &&
              expect.same(
                resolved.withSharedConfig(defaults, environment).deterministicConfigHash,
                effective.deterministicConfigHash
              ) &&
              expect.same(
                shared.fieldsAddedOrdinals.currencySnapshotProtocolV1For(environment).value.value,
                effective.currencySnapshotProtocolV1ActivationOrdinal
              )
            }
          )
    }
  }

}
