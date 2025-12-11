package io.constellationnetwork.dag.l1.cli

import cats.data.NonEmptySet
import cats.syntax.all._

import io.constellationnetwork.dag.l1.config.types._
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.env.env._
import io.constellationnetwork.node.shared.cli.opts.trustRatingsPathOpts
import io.constellationnetwork.node.shared.cli.{CliMethod, CollateralAmountOpts, L0PeerOpts}
import io.constellationnetwork.node.shared.config.types._
import io.constellationnetwork.node.shared.infrastructure.{DagL1, L1Layer}
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.cluster.PeerToJoin
import io.constellationnetwork.schema.peer.L0Peer

import com.monovore.decline.Opts
import fs2.io.file.Path

object method {

  sealed trait Run extends CliMethod {
    val l0Peer: L0Peer

    def appConfig(c: AppConfigReader, shared: SharedConfig): AppConfig = AppConfig(
      c.consensus,
      c.dataConsensus,
      c.swap,
      c.tokenLock,
      c.transactionLimit,
      shared
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
    allowanceListPath: Option[AllowanceListPath]
  ) extends Run

  object RunInitialValidator {

    def opts(l1Layer: L1Layer) = Opts.subcommand("run-initial-validator", "Run initial validator mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        AppEnvironment.opts,
        http.opts(l1Layer),
        L0PeerOpts.opts,
        SeedListPath.opts,
        CollateralAmountOpts.opts,
        trustRatingsPathOpts,
        SeedListPath.priorityOpts,
        AllowanceListPath.opts
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
    seedlistPath: Option[SeedListPath],
    collateralAmount: Option[Amount],
    trustRatingsPath: Option[Path],
    prioritySeedlistPath: Option[SeedListPath],
    majorityForkPeerIds: NonEmptySet[PeerToJoin],
    allowanceListPath: Option[AllowanceListPath]
  ) extends Run

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
    allowanceListPath: Option[AllowanceListPath]
  ) extends Run

  object RunValidator {

    def opts(l1Layer: L1Layer) = Opts.subcommand("run-validator", "Run validator mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        AppEnvironment.opts,
        http.opts(l1Layer),
        L0PeerOpts.opts,
        SeedListPath.opts,
        CollateralAmountOpts.opts,
        trustRatingsPathOpts,
        SeedListPath.priorityOpts,
        AllowanceListPath.opts
      ).mapN(RunValidator.apply)
    }
  }

  def opts(l1Layer: L1Layer): Opts[Run] =
    RunInitialValidator.opts(l1Layer).orElse(RunValidator.opts(l1Layer))
}
