package org.tessellation.dag.l1.cli

import cats.syntax.contravariantSemigroupal._

import scala.concurrent.duration.{DurationDouble, DurationInt}

import org.tessellation.cli.env.{KeyAlias, Password, StorePath}
import org.tessellation.dag.block.config.BlockValidatorConfig
import org.tessellation.dag.l1.config.types.{AppConfig, DBConfig}
import org.tessellation.dag.l1.domain.consensus.block.config.ConsensusConfig
import org.tessellation.ext.decline.decline._
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.L0Peer
import org.tessellation.sdk.cli.{CliMethod, L0PeerOpts}
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
          activeRetention = 2.seconds,
          seenRetention = 2.minutes
        ),
        daemon = GossipDaemonConfig(
          fanout = 2,
          interval = 0.2.seconds,
          maxConcurrentHandlers = 20
        )
      ),
      blockValidator = BlockValidatorConfig(
        requiredUniqueSigners = 3
      ),
      consensus = ConsensusConfig(
        peersCount = 2,
        tipsCount = 2,
        timeout = 5.seconds
      ),
      healthCheck = HealthCheckConfig(
        removeUnresponsiveParallelPeersAfter = 10.seconds,
        ping = PingHealthCheckConfig(
          concurrentChecks = 3,
          defaultCheckTimeout = 10.seconds,
          defaultCheckAttempts = 3,
          ensureCheckInterval = 10.seconds
        ),
        peerDeclaration = PeerDeclarationHealthCheckConfig(
          receiveTimeout = 20.seconds,
          triggerInterval = 10.seconds
        )
      )
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
    whitelistingPath: Option[Path]
  ) extends Run

  object RunInitialValidator {
    val whitelistingPathOpts: Opts[Option[Path]] = Opts.option[Path]("whitelisting", "").orNone

    val opts = Opts.subcommand("run-initial-validator", "Run initial validator mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        AppEnvironment.opts,
        http.opts,
        db.opts,
        L0PeerOpts.opts,
        whitelistingPathOpts
      ).mapN(RunInitialValidator(_, _, _, _, _, _, _, _))
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
    whitelistingPath: Option[Path]
  ) extends Run

  object RunValidator {
    val whitelistingPathOpts: Opts[Option[Path]] = Opts.option[Path]("whitelisting", "").orNone

    val opts = Opts.subcommand("run-validator", "Run validator mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        AppEnvironment.opts,
        http.opts,
        db.opts,
        L0PeerOpts.opts,
        whitelistingPathOpts
      ).mapN(RunValidator(_, _, _, _, _, _, _, _))
    }
  }

  val opts: Opts[Run] =
    RunInitialValidator.opts.orElse(RunValidator.opts)
}
