package org.tessellation.currency.l0.cli

import cats.syntax.all._

import org.tessellation.currency.cli.{GlobalL0PeerOpts, L0TokenIdentifierOpts}
import org.tessellation.currency.l0.cli.http.{opts => httpOpts}
import org.tessellation.currency.l0.config.types._
import org.tessellation.env.AppEnvironment
import org.tessellation.env.env._
import org.tessellation.ext.decline.WithOpts
import org.tessellation.node.shared.cli._
import org.tessellation.node.shared.cli.opts.{genesisBalancesOpts, genesisPathOpts, trustRatingsPathOpts}
import org.tessellation.node.shared.config.types._
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.peer.L0Peer

import com.monovore.decline.Opts
import eu.timepit.refined.auto._
import fs2.io.file.Path

object method {

  sealed trait Run extends CliMethod {
    val globalL0Peer: L0Peer

    def appConfig(c: AppConfigReader, shared: SharedConfig): AppConfig = AppConfig(
      snapshot = c.snapshot,
      globalL0Peer = globalL0Peer,
      peerDiscovery = c.peerDiscovery,
      shared = shared
    )

    val stateChannelAllowanceLists = None

    val l0SeedlistPath = None

  }

  case class CreateGenesis(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
    genesisBalancesPath: Path,
    seedlistPath: Option[SeedListPath],
    prioritySeedlistPath: Option[SeedListPath],
    collateralAmount: Option[Amount],
    globalL0Peer: L0Peer,
    trustRatingsPath: Option[Path],
    lastKryoHashOrdinal: SnapshotOrdinal
  ) extends Run

  object CreateGenesis extends WithOpts[CreateGenesis] {

    val opts: Opts[CreateGenesis] = Opts.subcommand("create-genesis", "Create genesis snapshot") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        httpOpts,
        AppEnvironment.opts,
        genesisBalancesOpts,
        SeedListPath.opts,
        SeedListPath.priorityOpts,
        CollateralAmountOpts.opts,
        GlobalL0PeerOpts.opts,
        trustRatingsPathOpts,
        Opts.apply(SnapshotOrdinal.MinValue)
      ).mapN(CreateGenesis.apply)
    }
  }

  case class RunGenesis(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
    genesisPath: Path,
    seedlistPath: Option[SeedListPath],
    prioritySeedlistPath: Option[SeedListPath],
    collateralAmount: Option[Amount],
    globalL0Peer: L0Peer,
    trustRatingsPath: Option[Path],
    lastKryoHashOrdinal: SnapshotOrdinal
  ) extends Run

  object RunGenesis extends WithOpts[RunGenesis] {

    val opts: Opts[RunGenesis] = Opts.subcommand("run-genesis", "Run genesis mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        httpOpts,
        AppEnvironment.opts,
        genesisPathOpts,
        SeedListPath.opts,
        SeedListPath.priorityOpts,
        CollateralAmountOpts.opts,
        GlobalL0PeerOpts.opts,
        trustRatingsPathOpts,
        Opts.apply(SnapshotOrdinal.MinValue)
      ).mapN(RunGenesis.apply)
    }
  }

  case class RunValidator(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
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
