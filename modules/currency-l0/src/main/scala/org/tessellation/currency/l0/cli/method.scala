package org.tessellation.currency.l0.cli

import cats.syntax.contravariantSemigroupal._

import scala.concurrent.duration._

import org.tessellation.cli.env.{KeyAlias, Password, StorePath}
import org.tessellation.currency.cli.{GlobalL0PeerOpts, L0TokenIdentifierOpts}
import org.tessellation.currency.l0.cli.http.{opts => httpOpts}
import org.tessellation.currency.l0.config.types.AppConfig
import org.tessellation.ext.decline.WithOpts
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.L0Peer
import org.tessellation.sdk.cli._
import org.tessellation.sdk.cli.opts.{genesisPathOpts, seedlistPathOpts, trustRatingsPathOpts}
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.types._

import com.monovore.decline.Opts
import eu.timepit.refined.auto._
import fs2.io.file.Path

object method {

  sealed trait Run extends CliMethod {
    val snapshotConfig: SnapshotConfig

    val globalL0Peer: L0Peer
    val identifier: Address

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
      collateral = collateralConfig(environment, collateralAmount),
      globalL0Peer = globalL0Peer
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
    collateralAmount: Option[Amount],
    globalL0Peer: L0Peer,
    identifier: Address,
    trustRatingsPath: Option[Path]
  ) extends Run

  object RunGenesis extends WithOpts[RunGenesis] {

    val opts: Opts[RunGenesis] = Opts.subcommand("run-genesis", "Run genesis mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        httpOpts,
        AppEnvironment.opts,
        snapshot.opts,
        genesisPathOpts,
        seedlistPathOpts,
        CollateralAmountOpts.opts,
        GlobalL0PeerOpts.opts,
        L0TokenIdentifierOpts.opts,
        trustRatingsPathOpts
      ).mapN(RunGenesis.apply)
    }
  }

  case class RunValidator(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
    snapshotConfig: SnapshotConfig,
    seedlistPath: Option[Path],
    collateralAmount: Option[Amount],
    globalL0Peer: L0Peer,
    identifier: Address,
    trustRatingsPath: Option[Path]
  ) extends Run

  object RunValidator extends WithOpts[RunValidator] {

    val opts: Opts[RunValidator] = Opts.subcommand("run-validator", "Run validator mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        httpOpts,
        AppEnvironment.opts,
        snapshot.opts,
        seedlistPathOpts,
        CollateralAmountOpts.opts,
        GlobalL0PeerOpts.opts,
        L0TokenIdentifierOpts.opts,
        trustRatingsPathOpts
      ).mapN(RunValidator.apply)
    }
  }

  val opts: Opts[Run] =
    RunGenesis.opts.orElse(RunValidator.opts)
}
