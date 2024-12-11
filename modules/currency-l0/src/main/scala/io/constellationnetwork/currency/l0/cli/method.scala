package io.constellationnetwork.currency.l0.cli

import cats.data.NonEmptySet
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.cli.{GlobalL0PeerOpts, L0TokenIdentifierOpts}
import io.constellationnetwork.currency.l0.cli.http.{opts => httpOpts}
import io.constellationnetwork.currency.l0.config.types._
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.env.env._
import io.constellationnetwork.ext.decline.WithOpts
import io.constellationnetwork.node.shared.cli._
import io.constellationnetwork.node.shared.cli.opts.{genesisBalancesOpts, genesisPathOpts, trustRatingsPathOpts}
import io.constellationnetwork.node.shared.config.types._
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.cluster.PeerToJoin
import io.constellationnetwork.schema.peer.L0Peer

import com.monovore.decline.Opts
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
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
      c.addresses,
      c.allowSpends,
      c.tokenLocks,
      c.lastGlobalSnapshotsSync,
      c.validationErrorStorage
    )
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

  case class RunValidatorWithJoinAttempt(
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
    trustRatingsPath: Option[Path],
    majorityForkPeerIds: NonEmptySet[PeerToJoin]
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
