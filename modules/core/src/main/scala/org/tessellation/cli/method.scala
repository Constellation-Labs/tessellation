package org.tessellation.cli

import cats.syntax.contravariantSemigroupal._

import scala.concurrent.duration._

import org.tessellation.cli.db
import org.tessellation.cli.env.{KeyAlias, Password, StorePath}
import org.tessellation.config.types._
import org.tessellation.ext.decline.WithOpts
import org.tessellation.ext.decline.decline._
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.node.NodeState
import org.tessellation.sdk.cli.opts.{genesisPathOpts, seedlistPathOpts, trustRatingsPathOpts}
import org.tessellation.sdk.cli.{CliMethod, CollateralAmountOpts, snapshot}
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.types._
import org.tessellation.security.hash.Hash

import com.monovore.decline.Opts
import com.monovore.decline.refined._
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.io.file.Path

object method {

  sealed trait Run extends CliMethod {
    val dbConfig: DBConfig

    val snapshotConfig: SnapshotConfig

    val appConfig: AppConfig = AppConfig(
      environment = environment,
      http = httpConfig,
      db = dbConfig,
      gossip = GossipConfig(
        storage = RumorStorageConfig(
          peerRumorsCapacity = 50L,
          activeCommonRumorsCapacity = 20L,
          seenCommonRumorsCapacity = 50L
        ),
        daemon = GossipDaemonConfig(
          peerRound = GossipRoundConfig(
            fanout = 1,
            interval = 0.2.seconds,
            maxConcurrentRounds = 8
          ),
          commonRound = GossipRoundConfig(
            fanout = 1,
            interval = 0.5.seconds,
            maxConcurrentRounds = 4
          )
        )
      ),
      trust = TrustConfig(
        TrustDaemonConfig(
          10.minutes
        )
      ),
      healthCheck = healthCheckConfig(false),
      snapshot = snapshotConfig,
      collateral = collateralConfig(environment, collateralAmount),
      rewards = RewardsConfig()
    )

    val stateAfterJoining: NodeState = NodeState.WaitingForDownload

  }

  case class RunGenesis(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    dbConfig: DBConfig,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
    snapshotConfig: SnapshotConfig,
    genesisPath: Path,
    seedlistPath: Option[Path],
    collateralAmount: Option[Amount],
    startingEpochProgress: EpochProgress,
    trustRatingsPath: Option[Path]
  ) extends Run

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
        snapshot.opts,
        genesisPathOpts,
        seedlistPathOpts,
        CollateralAmountOpts.opts,
        startingEpochProgressOpts,
        trustRatingsPathOpts
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
    snapshotConfig: SnapshotConfig,
    seedlistPath: Option[Path],
    collateralAmount: Option[Amount],
    rollbackHash: Hash,
    trustRatingsPath: Option[Path]
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
        snapshot.opts,
        seedlistPathOpts,
        CollateralAmountOpts.opts,
        rollbackHashOpts,
        trustRatingsPathOpts
      ).mapN(RunRollback.apply)
    }
  }

  case class RunValidator(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    dbConfig: DBConfig,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
    snapshotConfig: SnapshotConfig,
    seedlistPath: Option[Path],
    collateralAmount: Option[Amount],
    trustRatingsPath: Option[Path]
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
        snapshot.opts,
        seedlistPathOpts,
        CollateralAmountOpts.opts,
        trustRatingsPathOpts
      ).mapN(RunValidator.apply)
    }
  }

  val opts: Opts[Run] =
    RunGenesis.opts.orElse(RunValidator.opts).orElse(RunRollback.opts)
}
