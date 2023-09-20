package org.tessellation.cli

import cats.syntax.contravariantSemigroupal._
import cats.syntax.eq._

import scala.concurrent.duration._

import org.tessellation.cli.env._
import org.tessellation.cli.http
import org.tessellation.cli.incremental._
import org.tessellation.config.types._
import org.tessellation.ext.decline.WithOpts
import org.tessellation.ext.decline.decline._
import org.tessellation.infrastructure.statechannel.StateChannelAllowanceLists
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.node.NodeState
import org.tessellation.sdk.cli._
import org.tessellation.sdk.cli.opts.{genesisPathOpts, trustRatingsPathOpts}
import org.tessellation.sdk.config.types._
import org.tessellation.security.hash.Hash

import com.monovore.decline.Opts
import com.monovore.decline.refined._
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.io.file.Path

object method {

  sealed trait Run extends CliMethod {
    val dbConfig: DBConfig

    val snapshotConfig: SnapshotConfig

    val appConfig: AppConfig = AppConfig(
      environment = environment,
      http = httpConfig,
      db = dbConfig,
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
      trust = TrustConfig(
        TrustDaemonConfig(
          10.minutes
        )
      ),
      healthCheck = healthCheckConfig(false),
      snapshot = snapshotConfig,
      collateral = collateralConfig(environment, collateralAmount),
      rewards = RewardsConfig(),
      stateChannelPullDelay = NonNegLong.MinValue,
      stateChannelPurgeDelay = NonNegLong(4L),
      peerDiscoveryDelay = PeerDiscoveryDelay(
        checkPeersAttemptDelay = 1.minute,
        checkPeersMaxDelay = 10.minutes,
        additionalDiscoveryDelay = 3.minutes,
        minPeers = 2
      ),
      proposalSelect = ProposalSelectConfig(trustMultiplier = 5.0)
    )

    val stateChannelAllowanceLists = StateChannelAllowanceLists.get(environment)

    val l0SeedlistPath = seedlistPath

    val prioritySeedlistPath: Option[SeedListPath]

    val stateAfterJoining: NodeState = NodeState.WaitingForDownload

    val lastFullGlobalSnapshotOrdinal: SnapshotOrdinal

  }

  case class RunGenesis(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    dbConfig: DBConfig,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
    snapshotConfig: SnapshotConfig,
    genesisPath: Path,
    seedlistPath: Option[SeedListPath],
    collateralAmount: Option[Amount],
    startingEpochProgress: EpochProgress,
    trustRatingsPath: Option[Path],
    prioritySeedlistPath: Option[SeedListPath]
  ) extends Run {

    val lastFullGlobalSnapshotOrdinal = SnapshotOrdinal.MinValue
  }

  object RunGenesis extends WithOpts[RunGenesis] {

    val startingEpochProgressOpts: Opts[EpochProgress] = Opts
      .option[NonNegLong]("startingEpochProgress", "Set starting progress for rewarding at the specific epoch")
      .map(EpochProgress(_))
      .withDefault(EpochProgress.MinValue)

    val opts: Opts[RunGenesis] = Opts.subcommand("run-genesis", "Run genesis mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        db.opts,
        http.opts,
        AppEnvironment.opts,
        snapshot.opts,
        genesisPathOpts,
        SeedListPath.opts,
        CollateralAmountOpts.opts,
        startingEpochProgressOpts,
        trustRatingsPathOpts,
        SeedListPath.priorityOpts
      ).mapN(RunGenesis.apply)
    }
  }

  case class RunRollback(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    dbConfig: DBConfig,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
    snapshotConfig: SnapshotConfig,
    seedlistPath: Option[SeedListPath],
    collateralAmount: Option[Amount],
    rollbackHash: Hash,
    lastFullGlobalSnapshotOrdinal: SnapshotOrdinal,
    trustRatingsPath: Option[Path],
    prioritySeedlistPath: Option[SeedListPath]
  ) extends Run

  object RunRollback extends WithOpts[RunRollback] {

    val rollbackHashOpts: Opts[Hash] = Opts.argument[Hash]("rollbackHash")

    val opts: Opts[RunRollback] = Opts.subcommand("run-rollback", "Run rollback mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        db.opts,
        http.opts,
        AppEnvironment.opts,
        snapshot.opts,
        SeedListPath.opts,
        CollateralAmountOpts.opts,
        rollbackHashOpts,
        lastFullGlobalSnapshotOrdinalOpts,
        trustRatingsPathOpts,
        SeedListPath.priorityOpts
      ).mapN {
        case (
              storePath,
              keyAlias,
              password,
              db,
              http,
              environment,
              snapshot,
              seedlistPath,
              collateralAmount,
              rollbackHash,
              lastGlobalSnapshot,
              trustRatingsPath,
              prioritySeedlistPath
            ) =>
          val lastGS =
            (if (environment === AppEnvironment.Dev) lastGlobalSnapshot else lastFullGlobalSnapshot.get(environment))
              .getOrElse(SnapshotOrdinal.MinValue)

          RunRollback(
            storePath,
            keyAlias,
            password,
            db,
            http,
            environment,
            snapshot,
            seedlistPath,
            collateralAmount,
            rollbackHash,
            lastGS,
            trustRatingsPath,
            prioritySeedlistPath
          )
      }
    }
  }

  case class RunValidator(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    dbConfig: DBConfig,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
    snapshotConfig: SnapshotConfig,
    seedlistPath: Option[SeedListPath],
    collateralAmount: Option[Amount],
    trustRatingsPath: Option[Path],
    lastFullGlobalSnapshotOrdinal: SnapshotOrdinal,
    prioritySeedlistPath: Option[SeedListPath]
  ) extends Run

  object RunValidator extends WithOpts[RunValidator] {

    val opts: Opts[RunValidator] = Opts.subcommand("run-validator", "Run validator mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        db.opts,
        http.opts,
        AppEnvironment.opts,
        snapshot.opts,
        SeedListPath.opts,
        CollateralAmountOpts.opts,
        trustRatingsPathOpts,
        lastFullGlobalSnapshotOrdinalOpts,
        SeedListPath.priorityOpts
      ).mapN {
        case (
              storePath,
              keyAlias,
              password,
              db,
              http,
              environment,
              snapshot,
              seedlistPath,
              collateralAmount,
              trustRatingsPath,
              lastGlobalSnapshot,
              prioritySeedlistPath
            ) =>
          val lastGS =
            (if (environment === AppEnvironment.Dev) lastGlobalSnapshot else lastFullGlobalSnapshot.get(environment))
              .getOrElse(SnapshotOrdinal.MinValue)

          RunValidator(
            storePath,
            keyAlias,
            password,
            db,
            http,
            environment,
            snapshot,
            seedlistPath,
            collateralAmount,
            trustRatingsPath,
            lastGS,
            prioritySeedlistPath
          )
      }
    }
  }

  val opts: Opts[Run] =
    RunGenesis.opts.orElse(RunValidator.opts).orElse(RunRollback.opts)
}
