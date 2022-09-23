package org.tessellation.dag.l1.cli

import cats.syntax.contravariantSemigroupal._

import scala.concurrent.duration.{DurationDouble, DurationInt}

import org.tessellation.cli.env.{KeyAlias, Password, StorePath}
import org.tessellation.dag.l1.config.types.{AppConfig, DBConfig}
import org.tessellation.dag.l1.domain.consensus.block.config.ConsensusConfig
import org.tessellation.ext.decline.decline._
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.L0Peer
import org.tessellation.sdk.cli.{CliMethod, CollateralAmountOpts, L0PeerOpts}
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.types._

import com.monovore.decline.Opts
import eu.timepit.refined.auto.autoRefineV
import fs2.io.file.Path

object method {

  sealed trait Run extends CliMethod {
    val dbConfig: DBConfig
    val l0Peer: L0Peer

    val stateAfterJoining: NodeState = NodeState.Ready

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
            maxConcurrentRounds = 4
          ),
          commonRound = GossipRoundConfig(
            fanout = 1,
            interval = 0.5.seconds,
            maxConcurrentRounds = 2
          )
        )
      ),
      consensus = ConsensusConfig(
        peersCount = 2,
        tipsCount = 2,
        timeout = 45.seconds,
        pullTxsCount = 100L
      ),
      healthCheck = healthCheckConfig(false),
      collateral = collateralConfig(environment, collateralAmount)
    )
  }

  case class RunInitialValidator(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    environment: AppEnvironment,
    httpConfig: HttpConfig,
    dbConfig: DBConfig,
    l0Peer: L0Peer,
    seedlistPath: Option[Path],
    collateralAmount: Option[Amount]
  ) extends Run

  object RunInitialValidator {
    val seedlistPathOpts: Opts[Option[Path]] = Opts.option[Path]("seedlist", "").orNone

    val opts = Opts.subcommand("run-initial-validator", "Run initial validator mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        AppEnvironment.opts,
        http.opts,
        db.opts,
        L0PeerOpts.opts,
        seedlistPathOpts,
        CollateralAmountOpts.opts
      ).mapN(RunInitialValidator(_, _, _, _, _, _, _, _, _))
    }
  }

  case class RunValidator(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    environment: AppEnvironment,
    httpConfig: HttpConfig,
    dbConfig: DBConfig,
    l0Peer: L0Peer,
    seedlistPath: Option[Path],
    collateralAmount: Option[Amount]
  ) extends Run

  object RunValidator {
    val seedlistPathOpts: Opts[Option[Path]] = Opts.option[Path]("seedlist", "").orNone

    val opts = Opts.subcommand("run-validator", "Run validator mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        AppEnvironment.opts,
        http.opts,
        db.opts,
        L0PeerOpts.opts,
        seedlistPathOpts,
        CollateralAmountOpts.opts
      ).mapN(RunValidator(_, _, _, _, _, _, _, _, _))
    }
  }

  val opts: Opts[Run] =
    RunInitialValidator.opts.orElse(RunValidator.opts)
}
