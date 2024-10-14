package io.constellationnetwork.currency.l1.cli

import cats.data.NonEmptySet
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.cli.{GlobalL0PeerOpts, L0TokenIdentifierOpts}
import io.constellationnetwork.dag.l1.cli.http
import io.constellationnetwork.dag.l1.config.types.{AppConfig, AppConfigReader}
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.env.env._
import io.constellationnetwork.node.shared.cli._
import io.constellationnetwork.node.shared.cli.opts.trustRatingsPathOpts
import io.constellationnetwork.node.shared.config.types._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.cluster.PeerToJoin
import io.constellationnetwork.schema.peer.L0Peer

import com.monovore.decline.Opts
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.io.file.Path

object method {

  sealed trait Run extends CliMethod {
    val l0Peer: L0Peer
    val globalL0Peer: L0Peer
    val identifier: Address

    def appConfig(c: AppConfigReader, shared: SharedConfig): AppConfig =
      AppConfig(c.consensus, c.dataConsensus, c.transactionLimit, shared)

    val stateChannelAllowanceLists = None

    val l0SeedlistPath = None

    val prioritySeedlistPath: Option[SeedListPath]

    override def nodeSharedConfig(c: SharedConfigReader): SharedConfig = SharedConfig(
      environment,
      c.gossip,
      httpConfig,
      c.leavingDelay,
      c.stateAfterJoining,
      CollateralConfig(
        amount = collateralAmount.getOrElse(Amount(NonNegLong.MinValue))
      ),
      c.trust.storage,
      c.priorityPeerIds.get(environment),
      c.snapshot.size,
      c.feeConfigs.get(environment).map(SortedMap.from(_)).getOrElse(SortedMap.empty),
      c.forkInfoStorage,
      c.lastKryoHashOrdinal,
      c.addresses
    )
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

  case class RunValidatorWithJoinAttempt(
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
    prioritySeedlistPath: Option[SeedListPath],
    majorityForkPeerIds: NonEmptySet[PeerToJoin]
  ) extends Run

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
