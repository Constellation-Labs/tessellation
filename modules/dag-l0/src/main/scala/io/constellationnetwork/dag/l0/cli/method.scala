package io.constellationnetwork.dag.l0.cli

import cats.data.NonEmptySet
import cats.syntax.all._

import io.constellationnetwork.dag.l0.config.types._
import io.constellationnetwork.env._
import io.constellationnetwork.env.env._
import io.constellationnetwork.ext.decline.WithOpts
import io.constellationnetwork.ext.decline.decline._
import io.constellationnetwork.node.shared.cli.opts.{genesisPathOpts, trustRatingsPathOpts}
import io.constellationnetwork.node.shared.cli.{CliMethod, CollateralAmountOpts}
import io.constellationnetwork.node.shared.config.types._
import io.constellationnetwork.node.shared.infrastructure.statechannel.StateChannelAllowanceLists
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.cluster.PeerToJoin
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.security.hash.Hash

import com.monovore.decline.Opts
import com.monovore.decline.refined._
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.io.file.Path

object method {

  sealed trait Run extends CliMethod {

    def appConfig(c: AppConfigReader, shared: SharedConfig): AppConfig = AppConfig(
      trust = c.trust,
      rewards = MainnetRewardsConfig.classicMainnetRewardsConfig,
      snapshot = c.snapshot,
      stateChannel = c.stateChannel,
      peerDiscovery = c.peerDiscovery,
      incremental = c.incremental,
      shared = shared
    )

    val environment: AppEnvironment

    val stateChannelAllowanceLists = StateChannelAllowanceLists.get(environment)

    val l0SeedlistPath = seedlistPath

    val prioritySeedlistPath: Option[SeedListPath]

  }

  case class RunGenesis(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    dbConfig: DBConfig,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
    genesisPath: Path,
    seedlistPath: Option[SeedListPath],
    collateralAmount: Option[Amount],
    startingEpochProgress: EpochProgress,
    trustRatingsPath: Option[Path],
    prioritySeedlistPath: Option[SeedListPath]
  ) extends Run {}

  object RunGenesis extends WithOpts[RunGenesis] {

    val startingEpochProgressOpts: Opts[EpochProgress] = Opts
      .option[NonNegLong]("startingEpochProgress", "Set starting progress for rewarding at the specific epoch")
      .map(EpochProgress(_))
      .withDefault(EpochProgress.MinValue)

    val opts: Opts[RunGenesis] = Opts.subcommand("run-genesis", "Run genesis mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        db.opts,
        http.opts,
        AppEnvironment.opts,
        genesisPathOpts,
        SeedListPath.opts,
        CollateralAmountOpts.opts,
        startingEpochProgressOpts,
        trustRatingsPathOpts,
        SeedListPath.priorityOpts
      ).mapN(RunGenesis.apply)
    }
  }

  case class RunRollback(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    dbConfig: DBConfig,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
    seedlistPath: Option[SeedListPath],
    collateralAmount: Option[Amount],
    rollbackHash: Hash,
    trustRatingsPath: Option[Path],
    prioritySeedlistPath: Option[SeedListPath]
  ) extends Run

  object RunRollback extends WithOpts[RunRollback] {

    val rollbackHashOpts: Opts[Hash] = Opts.argument[Hash]("rollbackHash")

    val opts: Opts[RunRollback] = Opts.subcommand("run-rollback", "Run rollback mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        db.opts,
        http.opts,
        AppEnvironment.opts,
        SeedListPath.opts,
        CollateralAmountOpts.opts,
        rollbackHashOpts,
        trustRatingsPathOpts,
        SeedListPath.priorityOpts
      ).mapN(RunRollback.apply)
    }
  }

  case class RunValidatorWithJoinAttempt(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    dbConfig: DBConfig,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
    seedlistPath: Option[SeedListPath],
    collateralAmount: Option[Amount],
    trustRatingsPath: Option[Path],
    prioritySeedlistPath: Option[SeedListPath],
    peerToJoinPool: NonEmptySet[PeerToJoin]
  ) extends Run

  case class RunValidator(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    dbConfig: DBConfig,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
    seedlistPath: Option[SeedListPath],
    collateralAmount: Option[Amount],
    trustRatingsPath: Option[Path],
    prioritySeedlistPath: Option[SeedListPath]
  ) extends Run

  object RunValidator extends WithOpts[RunValidator] {

    val opts: Opts[RunValidator] = Opts.subcommand("run-validator", "Run validator mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        db.opts,
        http.opts,
        AppEnvironment.opts,
        SeedListPath.opts,
        CollateralAmountOpts.opts,
        trustRatingsPathOpts,
        SeedListPath.priorityOpts
      ).mapN(RunValidator.apply)
    }
  }

  val opts: Opts[Run] =
    RunGenesis.opts.orElse(RunValidator.opts).orElse(RunRollback.opts)
}
