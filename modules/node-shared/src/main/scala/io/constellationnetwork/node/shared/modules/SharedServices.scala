package io.constellationnetwork.node.shared.modules

import java.security.KeyPair

import cats.Parallel
import cats.data.NonEmptySet
import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.all._

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.json.{JsonBrotliBinarySerializer, JsonSerializer}
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.config.types.{CollateralConfig, SharedConfig}
import io.constellationnetwork.node.shared.domain.cluster.services.{Cluster, Session}
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeAcceptanceManager
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.healthcheck.LocalHealthcheck
import io.constellationnetwork.node.shared.domain.node.UpdateNodeParametersAcceptanceManager
import io.constellationnetwork.node.shared.domain.nodeCollateral.UpdateNodeCollateralAcceptanceManager
import io.constellationnetwork.node.shared.domain.seedlist.SeedlistEntry
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage}
import io.constellationnetwork.node.shared.domain.statechannel.FeeCalculator
import io.constellationnetwork.node.shared.domain.swap.block.AllowSpendBlockAcceptanceManager
import io.constellationnetwork.node.shared.domain.tokenlock.block.TokenLockBlockAcceptanceManager
import io.constellationnetwork.node.shared.http.p2p.clients.NodeClient
import io.constellationnetwork.node.shared.infrastructure.block.processing.BlockAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.cluster.services.Cluster
import io.constellationnetwork.node.shared.infrastructure.gossip.Gossip
import io.constellationnetwork.node.shared.infrastructure.healthcheck.LocalHealthcheck
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.{Hasher, HasherSelector, SecurityProvider}

import eu.timepit.refined.types.numeric.NonNegLong
import fs2.concurrent.SignallingRef

object SharedServices {

  def make[F[_]: Async: Parallel: HasherSelector: SecurityProvider: Metrics: Supervisor: JsonSerializer: KryoSerializer, A <: CliMethod](
    cfg: SharedConfig,
    nodeId: PeerId,
    generation: Generation,
    keyPair: KeyPair,
    storages: SharedStorages[F],
    queues: SharedQueues[F],
    session: Session[F],
    nodeClient: NodeClient[F],
    validators: SharedValidators[F],
    seedlist: Option[Set[SeedlistEntry]],
    restartSignal: SignallingRef[F, Option[A]],
    versionHash: Hash,
    jarHash: Hash,
    collateral: CollateralConfig,
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]],
    environment: AppEnvironment,
    txHasher: Hasher[F]
  ): F[SharedServices[F, A]] =
    for {
      restartService <- RestartService.make(restartSignal, storages.cluster)

      cluster = Cluster
        .make[F](
          cfg.leavingDelay,
          cfg.http,
          nodeId,
          keyPair,
          storages.cluster,
          storages.session,
          storages.node,
          seedlist,
          restartService,
          versionHash,
          jarHash,
          environment
        )

      localHealthcheck <- LocalHealthcheck.make[F](nodeClient, storages.cluster)
      gossip <- HasherSelector[F].withCurrent(implicit hasher => Gossip.make[F](queues.rumor, nodeId, generation, keyPair))
      currencySnapshotAcceptanceManager <- CurrencySnapshotAcceptanceManager.make(
        cfg.fieldsAddedOrdinals,
        cfg.environment,
        cfg.lastGlobalSnapshotsSync,
        BlockAcceptanceManager.make[F](validators.currencyBlockValidator, txHasher),
        TokenLockBlockAcceptanceManager.make[F](validators.tokenLockBlockValidator),
        AllowSpendBlockAcceptanceManager.make[F](validators.allowSpendBlockValidator),
        collateral.amount,
        validators.currencyMessageValidator,
        validators.feeTransactionValidator,
        validators.globalSnapshotSyncValidator,
        storages.lastNGlobalSnapshot,
        storages.lastGlobalSnapshot
      )

      currencyEventsCutter = CurrencyEventsCutter.make[F](None)

      currencySnapshotValidator = CurrencySnapshotValidator.make[F](
        cfg.fieldsAddedOrdinals.tessellation3Migration.getOrElse(cfg.environment, SnapshotOrdinal.MinValue),
        CurrencySnapshotCreator.make[F](
          cfg.fieldsAddedOrdinals.tessellation3Migration.getOrElse(cfg.environment, SnapshotOrdinal.MinValue),
          currencySnapshotAcceptanceManager,
          None,
          cfg.snapshotSize,
          currencyEventsCutter,
          storages.currencySnapshotEventValidationError
        ),
        validators.signedValidator,
        None,
        None
      )
      currencySnapshotContextFns = CurrencySnapshotContextFunctions.make(
        currencySnapshotValidator
      )
      feeCalculator = FeeCalculator.make(cfg.feeConfigs)
      globalSnapshotStateChannelManager <- GlobalSnapshotStateChannelAcceptanceManager.make(stateChannelAllowanceLists)
      jsonBrotliBinarySerializer <- JsonBrotliBinarySerializer.forSync
      updateNodeParametersAcceptanceManager = UpdateNodeParametersAcceptanceManager.make(validators.updateNodeParametersValidator)
      updateDelegatedStakeAcceptanceManager = UpdateDelegatedStakeAcceptanceManager.make(
        validators.updateDelegatedStakeValidator
      )
      updateNodeCollateralAcceptanceManager = UpdateNodeCollateralAcceptanceManager.make(
        validators.updateNodeCollateralValidator
      )
      globalSnapshotAcceptanceManager = GlobalSnapshotAcceptanceManager.make(
        cfg.fieldsAddedOrdinals,
        cfg.metagraphsSync,
        cfg.environment,
        BlockAcceptanceManager.make[F](validators.blockValidator, txHasher),
        AllowSpendBlockAcceptanceManager.make[F](validators.allowSpendBlockValidator),
        TokenLockBlockAcceptanceManager.make[F](validators.tokenLockBlockValidator),
        GlobalSnapshotStateChannelEventsProcessor
          .make[F](
            validators.stateChannelValidator,
            globalSnapshotStateChannelManager,
            currencySnapshotContextFns,
            jsonBrotliBinarySerializer,
            feeCalculator
          ),
        updateNodeParametersAcceptanceManager,
        updateDelegatedStakeAcceptanceManager,
        updateNodeCollateralAcceptanceManager,
        validators.spendActionValidator,
        collateral.amount,
        cfg.delegatedStaking.withdrawalTimeLimit.getOrElse(cfg.environment, EpochProgress.MinValue)
      )
      globalSnapshotContextFns = GlobalSnapshotContextFunctions.make(
        globalSnapshotAcceptanceManager,
        updateDelegatedStakeAcceptanceManager,
        cfg.delegatedStaking.withdrawalTimeLimit.getOrElse(cfg.environment, EpochProgress.MinValue),
        cfg.fieldsAddedOrdinals.tessellation3Migration.getOrElse(cfg.environment, SnapshotOrdinal.MinValue)
      )
    } yield
      new SharedServices[F, A](
        localHealthcheck = localHealthcheck,
        cluster = cluster,
        session = session,
        gossip = gossip,
        globalSnapshotContextFns = globalSnapshotContextFns,
        currencySnapshotContextFns = currencySnapshotContextFns,
        currencySnapshotAcceptanceManager = currencySnapshotAcceptanceManager,
        currencyEventsCutter = currencyEventsCutter,
        restart = restartService,
        updateNodeParametersAcceptanceManager = updateNodeParametersAcceptanceManager,
        updateDelegatedStakeAcceptanceManager = updateDelegatedStakeAcceptanceManager,
        updateNodeCollateralAcceptanceManager = updateNodeCollateralAcceptanceManager
      ) {}
}

sealed abstract class SharedServices[F[_], A <: CliMethod] private (
  val localHealthcheck: LocalHealthcheck[F],
  val cluster: Cluster[F],
  val session: Session[F],
  val gossip: Gossip[F],
  val globalSnapshotContextFns: GlobalSnapshotContextFunctions[F],
  val currencySnapshotContextFns: CurrencySnapshotContextFunctions[F],
  val currencySnapshotAcceptanceManager: CurrencySnapshotAcceptanceManager[F],
  val currencyEventsCutter: CurrencyEventsCutter[F],
  val restart: RestartService[F, A],
  val updateNodeParametersAcceptanceManager: UpdateNodeParametersAcceptanceManager[F],
  val updateDelegatedStakeAcceptanceManager: UpdateDelegatedStakeAcceptanceManager[F],
  val updateNodeCollateralAcceptanceManager: UpdateNodeCollateralAcceptanceManager[F]
)
