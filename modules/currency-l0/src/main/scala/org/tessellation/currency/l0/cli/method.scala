package org.tessellation.currency.l0.cli

import cats.syntax.contravariantSemigroupal._

import scala.concurrent.duration._

import org.tessellation.cli.AppEnvironment
import org.tessellation.cli.env._
import org.tessellation.currency.cli.{GlobalL0PeerOpts, L0TokenIdentifierOpts}
import org.tessellation.currency.l0.cli.http.{opts => httpOpts}
import org.tessellation.currency.l0.config.types.AppConfig
import org.tessellation.ext.decline.WithOpts
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.L0Peer
import org.tessellation.sdk.cli._
import org.tessellation.sdk.cli.opts.{genesisBalancesOpts, genesisPathOpts, trustRatingsPathOpts}
import org.tessellation.sdk.config.types._

import com.monovore.decline.Opts
import eu.timepit.refined.auto._
import fs2.io.file.Path

object method {

  sealed trait Run extends CliMethod {
    val snapshotConfig: SnapshotConfig

    val globalL0Peer: L0Peer

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
      globalL0Peer = globalL0Peer,
      peerDiscoveryDelay = PeerDiscoveryDelay(
        checkPeersAttemptDelay = 1.minute,
        checkPeersMaxDelay = 10.minutes,
        additionalDiscoveryDelay = 0.minutes,
        minPeers = 1
      ),
      proposalSelect = ProposalSelectConfig(trustMultiplier = 5.0),
      snapshotSizeConfig = snapshotSizeConfig
    )

    val stateAfterJoining: NodeState = NodeState.WaitingForDownload

    val stateChannelAllowanceLists = None

    val l0SeedlistPath = None

  }

  case class CreateGenesis(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
    snapshotConfig: SnapshotConfig,
    genesisBalancesPath: Path,
    seedlistPath: Option[SeedListPath],
    prioritySeedlistPath: Option[SeedListPath],
    collateralAmount: Option[Amount],
    globalL0Peer: L0Peer,
    trustRatingsPath: Option[Path]
  ) extends Run

  object CreateGenesis extends WithOpts[CreateGenesis] {

    val opts: Opts[CreateGenesis] = Opts.subcommand("create-genesis", "Create genesis snapshot") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        httpOpts,
        AppEnvironment.opts,
        snapshot.opts,
        genesisBalancesOpts,
        SeedListPath.opts,
        SeedListPath.priorityOpts,
        CollateralAmountOpts.opts,
        GlobalL0PeerOpts.opts,
        trustRatingsPathOpts
      ).mapN(CreateGenesis.apply)
    }
  }

  case class RunGenesis(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
    snapshotConfig: SnapshotConfig,
    genesisPath: Path,
    seedlistPath: Option[SeedListPath],
    prioritySeedlistPath: Option[SeedListPath],
    collateralAmount: Option[Amount],
    globalL0Peer: L0Peer,
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
        SeedListPath.opts,
        SeedListPath.priorityOpts,
        CollateralAmountOpts.opts,
        GlobalL0PeerOpts.opts,
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
    seedlistPath: Option[SeedListPath],
    prioritySeedlistPath: Option[SeedListPath],
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
        SeedListPath.opts,
        SeedListPath.priorityOpts,
        CollateralAmountOpts.opts,
        GlobalL0PeerOpts.opts,
        L0TokenIdentifierOpts.opts,
        trustRatingsPathOpts
      ).mapN(RunValidator.apply)
    }
  }

  case class RunRollback(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
    snapshotConfig: SnapshotConfig,
    seedlistPath: Option[SeedListPath],
    prioritySeedlistPath: Option[SeedListPath],
    collateralAmount: Option[Amount],
    globalL0Peer: L0Peer,
    identifier: Address,
    trustRatingsPath: Option[Path]
  ) extends Run

  object RunRollback extends WithOpts[RunRollback] {

    val opts: Opts[RunRollback] = Opts.subcommand("run-rollback", "Run rollback mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        httpOpts,
        AppEnvironment.opts,
        snapshot.opts,
        SeedListPath.opts,
        SeedListPath.priorityOpts,
        CollateralAmountOpts.opts,
        GlobalL0PeerOpts.opts,
        L0TokenIdentifierOpts.opts,
        trustRatingsPathOpts
      ).mapN(RunRollback.apply)
    }
  }

  val opts: Opts[Run] =
    CreateGenesis.opts.orElse(RunGenesis.opts).orElse(RunValidator.opts).orElse(RunRollback.opts)
}
