package org.tessellation.currency.l1.cli

import cats.data.NonEmptySet
import cats.syntax.contravariantSemigroupal._
import cats.syntax.option._

import scala.concurrent.duration.{DurationDouble, DurationInt}

import org.tessellation.cli.AppEnvironment
import org.tessellation.cli.env._
import org.tessellation.currency.cli.{GlobalL0PeerOpts, L0TokenIdentifierOpts}
import org.tessellation.dag.l1.cli.http
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
    val globalL0Peer: L0Peer
    val identifier: Address

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
            "d894a728b3c347ea46828eba3f4f69153522cf7c8429656ac736ac38856fd2fcb440803050ac5db90a4255c9d1deddbd42585768806538ea7504e068785c95b9",
            "c43491168699803ce36649da2b6862249a8f5e8bb7702596dcfbd29228867ecfa35663a067610cce3bc7cc624e617c7c765a0131690a439827655ad88ff4c010",
            "b907307a13b8c2ba6ebbf5b561cbd39bcf3c2aad83ee691c60038d8e62471066ca5c9853ff996be6750f0a848aa98dfe6c9a032c4a951a24884ebcce598f896a"
          )
        ).some

      case AppEnvironment.Integrationnet =>
        makeStateChannelAllowance(
          Address("DAG5kfY9GoHF1CYaY8tuRJxmB3JSzAEARJEAkA2C"),
          NonEmptySet.of(
            "fe377cfa08f234ae87c5a4966b6fbbfd2ad12bff5b27b852c5eb0b885aeac1abfce7c580a8541df0402bd5ac968f720672864661a932f9bc5e98f6138d2f13bc",
            "2bc01ce069fc8a23a786a2cd94e36942558d130e0898465e1742833aeb4944d8cb19dada7bfc5f56f64b838e84298436c7cb7fd22114fd8aa70b5fd8e0a7a679",
            "50707f3fa88cbbc07a64ce274b6cbbfbed98b8c1a836efab81582332493475d27a95dd30ef822f29d862f455988a160223e835c838d24110d2a9038c1d5f4ba7"
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
    globalL0Peer: L0Peer,
    identifier: Address,
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
        GlobalL0PeerOpts.opts,
        L0TokenIdentifierOpts.opts,
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
    globalL0Peer: L0Peer,
    identifier: Address,
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
        GlobalL0PeerOpts.opts,
        L0TokenIdentifierOpts.opts,
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
