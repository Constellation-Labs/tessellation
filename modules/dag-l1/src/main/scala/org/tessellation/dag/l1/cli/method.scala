package org.tessellation.dag.l1.cli

import cats.syntax.all._

import scala.concurrent.duration.{DurationDouble, DurationInt}

import org.tessellation.dag.l1.config.types.AppConfig
import org.tessellation.dag.l1.domain.consensus.block.config.ConsensusConfig
import org.tessellation.env.AppEnvironment
import org.tessellation.env.env._
import org.tessellation.node.shared.cli.hashLogic.{lastKryoHashOrdinal, lastKryoHashOrdinalOpts}
import org.tessellation.node.shared.cli.opts.trustRatingsPathOpts
import org.tessellation.node.shared.cli.{CliMethod, CollateralAmountOpts, L0PeerOpts}
import org.tessellation.node.shared.config.types._
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.L0Peer

import com.monovore.decline.Opts
import eu.timepit.refined.auto.autoRefineV
import fs2.io.file.Path

object method {

  sealed trait Run extends CliMethod {
    val l0Peer: L0Peer

    val stateAfterJoining: NodeState = NodeState.Ready

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

    val stateChannelAllowanceLists = None

    val l0SeedlistPath = None

    val prioritySeedlistPath: Option[SeedListPath]
  }

  case class RunInitialValidator(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    environment: AppEnvironment,
    httpConfig: HttpConfig,
    l0Peer: L0Peer,
    seedlistPath: Option[SeedListPath],
    collateralAmount: Option[Amount],
    trustRatingsPath: Option[Path],
    prioritySeedlistPath: Option[SeedListPath],
    lastKryoHashOrdinal: SnapshotOrdinal
  ) extends Run

  object RunInitialValidator {

    val opts = Opts.subcommand("run-initial-validator", "Run initial validator mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        AppEnvironment.opts,
        http.opts,
        L0PeerOpts.opts,
        SeedListPath.opts,
        CollateralAmountOpts.opts,
        trustRatingsPathOpts,
        SeedListPath.priorityOpts,
        lastKryoHashOrdinalOpts
      ).mapN {
        case (
              storePath,
              keyAlias,
              password,
              environment,
              http,
              l0Peer,
              seedlistPath,
              collateralAmount,
              trustRatingsPath,
              prioritySeedlistPath,
              lastKryoHash
            ) =>
          val lastKH =
            (if (environment === AppEnvironment.Dev) lastKryoHash else lastKryoHashOrdinal.get(environment))
              .getOrElse(SnapshotOrdinal.MinValue)

          RunInitialValidator(
            storePath,
            keyAlias,
            password,
            environment,
            http,
            l0Peer,
            seedlistPath,
            collateralAmount,
            trustRatingsPath,
            prioritySeedlistPath,
            lastKH
          )
      }
    }
  }

  case class RunValidator(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    environment: AppEnvironment,
    httpConfig: HttpConfig,
    l0Peer: L0Peer,
    seedlistPath: Option[SeedListPath],
    collateralAmount: Option[Amount],
    trustRatingsPath: Option[Path],
    prioritySeedlistPath: Option[SeedListPath],
    lastKryoHashOrdinal: SnapshotOrdinal
  ) extends Run

  object RunValidator {

    val opts = Opts.subcommand("run-validator", "Run validator mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        AppEnvironment.opts,
        http.opts,
        L0PeerOpts.opts,
        SeedListPath.opts,
        CollateralAmountOpts.opts,
        trustRatingsPathOpts,
        SeedListPath.priorityOpts,
        lastKryoHashOrdinalOpts
      ).mapN {
        case (
              storePath,
              keyAlias,
              password,
              environment,
              http,
              l0Peer,
              seedlistPath,
              collateralAmount,
              trustRatingsPath,
              prioritySeedlistPath,
              lastKryoHash
            ) =>
          val lastKH =
            (if (environment === AppEnvironment.Dev) lastKryoHash else lastKryoHashOrdinal.get(environment))
              .getOrElse(SnapshotOrdinal.MinValue)

          RunValidator(
            storePath,
            keyAlias,
            password,
            environment,
            http,
            l0Peer,
            seedlistPath,
            collateralAmount,
            trustRatingsPath,
            prioritySeedlistPath,
            lastKH
          )
      }
    }
  }

  val opts: Opts[Run] =
    RunInitialValidator.opts.orElse(RunValidator.opts)
}
