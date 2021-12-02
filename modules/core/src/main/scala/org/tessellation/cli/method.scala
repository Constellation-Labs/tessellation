package org.tessellation.cli

import cats.syntax.contravariantSemigroupal._

import scala.concurrent.duration._

import org.tessellation.cli.db
import org.tessellation.cli.env.{KeyAlias, Password, StorePath}
import org.tessellation.config.AppEnvironment
import org.tessellation.config.types._
import org.tessellation.ext.decline.WithOpts
import org.tessellation.ext.decline.decline._

import com.monovore.decline.Opts
import fs2.io.file.Path

object method {

  sealed trait CliMethod

  sealed trait Run extends CliMethod {
    val keyStore: StorePath
    val alias: KeyAlias
    val password: Password
    val dbConfig: DBConfig
    val httpConfig: HttpConfig
    val environment: AppEnvironment

    val appConfig: AppConfig = AppConfig(
      environment = environment,
      httpConfig = httpConfig,
      dbConfig = dbConfig,
      gossipConfig = GossipConfig(
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
      trustConfig = TrustConfig(
        TrustDaemonConfig(
          10.minutes
        )
      )
    )
  }

  val appEnvOpts: Opts[AppEnvironment] = Opts
    .option[AppEnvironment]("env", help = "Environment", short = "e")
    .orElse(Opts.env[AppEnvironment]("CL_APP_ENV", help = "Environment"))
    .withDefault(AppEnvironment.Testnet)

  case class RunGenesis(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    dbConfig: DBConfig,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
    genesisPath: Path
  ) extends Run

  object RunGenesis extends WithOpts[RunGenesis] {

    val genesisPathOpts: Opts[Path] = Opts.argument[Path]("genesis")

    val opts = Opts.subcommand("run-genesis", "Run genesis mode") {
      (StorePath.opts, KeyAlias.opts, Password.opts, db.opts, http.opts, appEnvOpts, genesisPathOpts)
        .mapN(RunGenesis.apply(_, _, _, _, _, _, _))
    }
  }

  case class RunValidator(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    dbConfig: DBConfig,
    httpConfig: HttpConfig,
    environment: AppEnvironment
  ) extends Run

  object RunValidator extends WithOpts[RunValidator] {

    val opts = Opts.subcommand("run-validator", "Run validator mode") {
      (StorePath.opts, KeyAlias.opts, Password.opts, db.opts, http.opts, appEnvOpts)
        .mapN(RunValidator.apply(_, _, _, _, _, _))
    }
  }

  val opts: Opts[CliMethod] =
    RunGenesis.opts.orElse(RunValidator.opts)
}
