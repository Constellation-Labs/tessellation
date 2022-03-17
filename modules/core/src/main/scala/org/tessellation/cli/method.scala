package org.tessellation.cli

import cats.syntax.contravariantSemigroupal._

import scala.concurrent.duration._

import org.tessellation.cli.db
import org.tessellation.cli.env.{KeyAlias, Password, StorePath}
import org.tessellation.config.types._
import org.tessellation.ext.decline.WithOpts
import org.tessellation.ext.decline.decline._
import org.tessellation.sdk.cli.CliMethod
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.types._

import com.monovore.decline.Opts
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
          activeRetention = 2.seconds,
          seenRetention = 2.minutes
        ),
        daemon = GossipDaemonConfig(
          fanout = 2,
          interval = 0.2.seconds,
          maxConcurrentHandlers = 20
        )
      ),
      trust = TrustConfig(
        TrustDaemonConfig(
          10.minutes
        )
      ),
      healthCheck = healthCheckConfig,
      snapshot = snapshotConfig
    )

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
    whitelistingPath: Option[Path]
  ) extends Run

  object RunGenesis extends WithOpts[RunGenesis] {

    val genesisPathOpts: Opts[Path] = Opts.argument[Path]("genesis")

    val whitelistingPathOpts: Opts[Option[Path]] = Opts.option[Path]("whitelisting", "").orNone

    val opts = Opts.subcommand("run-genesis", "Run genesis mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        db.opts,
        http.opts,
        AppEnvironment.opts,
        snapshot.opts,
        genesisPathOpts,
        whitelistingPathOpts
      ).mapN(RunGenesis.apply(_, _, _, _, _, _, _, _, _))
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
    whitelistingPath: Option[Path]
  ) extends Run

  object RunValidator extends WithOpts[RunValidator] {

    val whitelistingPathOpts: Opts[Option[Path]] = Opts.option[Path]("whitelisting", "").orNone

    val opts = Opts.subcommand("run-validator", "Run validator mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        db.opts,
        http.opts,
        AppEnvironment.opts,
        snapshot.opts,
        whitelistingPathOpts
      ).mapN(RunValidator.apply(_, _, _, _, _, _, _, _))
    }
  }

  val opts: Opts[Run] =
    RunGenesis.opts.orElse(RunValidator.opts)
}
