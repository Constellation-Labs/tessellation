package org.tessellation.dag.l1.cli

import cats.data.NonEmptySet
import cats.syntax.contravariantSemigroupal._
import cats.syntax.option._

import scala.concurrent.duration.{DurationDouble, DurationInt}

import org.tessellation.cli.AppEnvironment
import org.tessellation.cli.env._
import org.tessellation.dag.l1.config.types.AppConfig
import org.tessellation.dag.l1.domain.consensus.block.config.ConsensusConfig
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.{L0Peer, PeerId}
import org.tessellation.sdk.cli.opts.trustRatingsPathOpts
import org.tessellation.sdk.cli.{CliMethod, CollateralAmountOpts, L0PeerOpts}
import org.tessellation.sdk.config.types._

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

    val stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]] = environment match {
      case AppEnvironment.Dev     => None
      case AppEnvironment.Mainnet => None

      case AppEnvironment.Testnet =>
        makeStateChannelAllowance(
          Address("DAG8gMagrwoJ4nAMjbGx17WB5D6nqBEPZYChc3zH"),
          NonEmptySet.of(
            "f88874b15da4f772bb8a76936b923b138b83e3035ea23075b88e5bf599ccbb6ca1ee818605f87f5db9bcd4782abe74fa600dbf6f81c63d98c150425e592c27fa",
            "cf2b0795a4ed0d09b1331ae95bec954e2fe0c25962ad883e74b588e63de5cad1708140f1daf402996b50e79932bdf1e0c9519e09566437bf73ae6e99ac839024",
            "b0ed7562a3a66ce4ba7f4fd51e0f17fb59d2c7b1dd42dd14d6f57ba47467be5f36bb80fb5999c446f87cb70b8b715311691313c9623e37fb679ccdaf3016e537"
          )
        ).some

      case AppEnvironment.Integrationnet =>
        makeStateChannelAllowance(
          Address("DAG5kfY9GoHF1CYaY8tuRJxmB3JSzAEARJEAkA2C"),
          NonEmptySet.of(
            "197280d110642ff3af86bf846b6ca3a3b5861a0b0691cc376812d10b3ae4e7b3b0d489391e56343377e7efa13299635931961c623c1debaa49e645f31cb1ff1f",
            "388f6a85155018075047fc197e9e0d1f0101d422c2216a9d7603eee1c3aafa080053e51c835e4a870609de8349e7d0e6e852c5a2dd1e6a3e8b51c24baa8af28f",
            "e288f7cab826ea0c27fad7b0700171e60e96a68dcee1e7a942bc8a9950929c1c6a609d08ffc7114f17bc841e6d261972fcc862eadd08c81fe17bf99a1d9218e5"
          )
        ).some
    }

    val l0SeedlistPath: Option[SeedListPath] = None

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
    prioritySeedlistPath: Option[SeedListPath]
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
        SeedListPath.priorityOpts
      ).mapN(RunInitialValidator.apply)
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
    prioritySeedlistPath: Option[SeedListPath]
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
        SeedListPath.priorityOpts
      ).mapN(RunValidator.apply)
    }
  }

  val opts: Opts[Run] =
    RunInitialValidator.opts.orElse(RunValidator.opts)
}
