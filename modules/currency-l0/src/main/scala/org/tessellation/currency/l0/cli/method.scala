package org.tessellation.currency.l0.cli

import cats.syntax.contravariantSemigroupal._

import scala.concurrent.duration._

import org.tessellation.cli.env.{KeyAlias, Password, StorePath}
import org.tessellation.currency.cli.http
import org.tessellation.currency.l0.config.types._
import org.tessellation.ext.decline.WithOpts
import org.tessellation.ext.decline.decline._
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.node.NodeState
import org.tessellation.sdk.cli.{CliMethod, CollateralAmountOpts}
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.types._
import org.tessellation.schema.security.hash.Hash

import com.monovore.decline.Opts
import eu.timepit.refined.auto._
import fs2.io.file.Path

object method {

  sealed trait Run extends CliMethod {
    val snapshotConfig: SnapshotConfig

    val appConfig: AppConfig = AppConfig(
      environment = environment,
      http = httpConfig,
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
      healthCheck = healthCheckConfig(false),
      snapshot = snapshotConfig,
      collateral = collateralConfig(environment, collateralAmount)
    )

    val stateAfterJoining: NodeState = NodeState.WaitingForDownload

  }

  case class RunGenesis(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
    snapshotConfig: SnapshotConfig,
    genesisPath: Path,
    seedlistPath: Option[Path],
    collateralAmount: Option[Amount]
  ) extends Run

  object RunGenesis extends WithOpts[RunGenesis] {

    private val genesisPathOpts: Opts[Path] = Opts.argument[Path]("genesis")

    private val seedlistPathOpts: Opts[Option[Path]] = Opts.option[Path]("seedlist", "").orNone

    val opts: Opts[RunGenesis] = Opts.subcommand("run-genesis", "Run genesis mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        http.opts,
        AppEnvironment.opts,
        snapshot.opts,
        genesisPathOpts,
        seedlistPathOpts,
        CollateralAmountOpts.opts
      ).mapN(RunGenesis.apply)
    }
  }

  case class RunRollback(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
    snapshotConfig: SnapshotConfig,
    seedlistPath: Option[Path],
    collateralAmount: Option[Amount],
    rollbackHash: Hash
  ) extends Run

  case class RunValidator(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
    snapshotConfig: SnapshotConfig,
    seedlistPath: Option[Path],
    collateralAmount: Option[Amount]
  ) extends Run

  object RunValidator extends WithOpts[RunValidator] {

    private val seedlistPathOpts: Opts[Option[Path]] = Opts.option[Path]("seedlist", "").orNone

    val opts: Opts[RunValidator] = Opts.subcommand("run-validator", "Run validator mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        http.opts,
        AppEnvironment.opts,
        snapshot.opts,
        seedlistPathOpts,
        CollateralAmountOpts.opts
      ).mapN(RunValidator.apply)
    }
  }

  val opts: Opts[Run] =
    RunGenesis.opts.orElse(RunValidator.opts)
}
