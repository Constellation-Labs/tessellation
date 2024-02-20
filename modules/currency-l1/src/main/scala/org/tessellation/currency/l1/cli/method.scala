package org.tessellation.currency.l1.cli

import cats.syntax.all._

import org.tessellation.currency.cli.{GlobalL0PeerOpts, L0TokenIdentifierOpts}
import org.tessellation.dag.l1.cli.http
import org.tessellation.dag.l1.config.types.{AppConfig, AppConfigReader}
import org.tessellation.env.AppEnvironment
import org.tessellation.env.env._
import org.tessellation.node.shared.cli.opts.trustRatingsPathOpts
import org.tessellation.node.shared.cli.{CliMethod, CollateralAmountOpts, L0PeerOpts}
import org.tessellation.node.shared.config.types._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.peer.L0Peer

import com.monovore.decline.Opts
import fs2.io.file.Path

object method {

  sealed trait Run extends CliMethod {
    val l0Peer: L0Peer
    val globalL0Peer: L0Peer
    val identifier: Address

    def appConfig(c: AppConfigReader, shared: SharedConfig): AppConfig = AppConfig(c.consensus, shared)

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
