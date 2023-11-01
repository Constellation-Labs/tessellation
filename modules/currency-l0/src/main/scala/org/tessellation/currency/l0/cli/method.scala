package org.tessellation.currency.l0.cli

import cats.data.NonEmptySet
import cats.syntax.contravariantSemigroupal._
import cats.syntax.option._

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
import org.tessellation.schema.peer.{L0Peer, PeerId}
import org.tessellation.sdk.cli._
import org.tessellation.sdk.cli.opts.{genesisPathOpts, trustRatingsPathOpts}
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
      proposalSelect = ProposalSelectConfig(trustMultiplier = 5.0)
    )

    val stateAfterJoining: NodeState = NodeState.WaitingForDownload

    val stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]] = environment match {
      case AppEnvironment.Dev     => None
      case AppEnvironment.Mainnet => None

      case AppEnvironment.Testnet =>
        makeStateChannelAllowance(
          Address("DAG8gMagrwoJ4nAMjbGx17WB5D6nqBEPZYChc3zH"),
          NonEmptySet.of(
            "1ec3c11867e3cd984a31db77e053c4105c78115ae604503fd9b0ef03399efd41464ac6efdf54cb3cdaa480be5262e7bd3fd2e1b6cc6bdc6dc3cee94f31c90856",
            "1f1494da3bf0fdf70faff3fd21cebcd322b2b4d0abc5107924a0a70239afe12c480c9ca9f70ec125bee15781e93310f2a5d726c0f26b287785d009ef93bcaa77",
            "3fd28a8c11a56434b1806abc8f244a0db8896f3eb53951a9712a9e7085af88097290ef8169752b21a0f62f7ae4c5002db9cb46ba791a3253caf41cfa9cb3135a"
          )
        ).some

      case AppEnvironment.Integrationnet =>
        makeStateChannelAllowance(
          Address("DAG5kfY9GoHF1CYaY8tuRJxmB3JSzAEARJEAkA2C"),
          NonEmptySet.of(
            "a2496d09b7325f7e96d0f774c8fd45670779ec0614b672db23d2cf8342c13aaa6a64146996f8aed365eb12412c9b39f2eade3e45f610464212b8f69a660271d5",
            "49496e22cffa3314958aa12fc16c658ce5ed0a8da032823a6100c39d7ef5198221888350c1d40cd35c8892d779e9d50b4c8a0f542168c6a586967b1c6dd5b153",
            "d741b547225b6ba6f1ba38be192ab7550b7610ef54e7fee88a9666b79a12a6741d1565241fba5c2a812be66edd878824f927a42430ffba48fa0bd0264a5483bf"
          )
        ).some
    }

    val l0SeedlistPath: Option[SeedListPath] = None

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
    RunGenesis.opts.orElse(RunValidator.opts).orElse(RunRollback.opts)
}
