package io.constellationnetwork.node.shared.modules

import java.security.KeyPair

import cats.Parallel
import cats.data.NonEmptySet
import cats.effect.Async
import cats.effect.kernel.Ref
import cats.effect.std.Supervisor
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.domain.allowance_list.AllowanceListEntry
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.config.DefaultDelegatedRewardsConfigProvider
import io.constellationnetwork.node.shared.config.types.{CollateralConfig, SharedConfig}
import io.constellationnetwork.node.shared.domain.cluster.services.{Cluster, Session}
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeAcceptanceManager
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.healthcheck.LocalHealthcheck
import io.constellationnetwork.node.shared.domain.node.UpdateNodeParametersAcceptanceManager
import io.constellationnetwork.node.shared.domain.nodeCollateral.UpdateNodeCollateralAcceptanceManager
import io.constellationnetwork.node.shared.domain.priceOracle.PriceStateUpdater
import io.constellationnetwork.node.shared.domain.statechannel.FeeCalculator
import io.constellationnetwork.node.shared.domain.swap.block.AllowSpendBlockAcceptanceManager
import io.constellationnetwork.node.shared.domain.tokenlock.block.TokenLockBlockAcceptanceManager
import io.constellationnetwork.node.shared.http.p2p.clients.NodeClient
import io.constellationnetwork.node.shared.infrastructure.block.processing.BlockAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.cluster.services.Cluster
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.ConsensusHealthStatus
import io.constellationnetwork.node.shared.infrastructure.gossip.{Gossip => GossipImpl}
import io.constellationnetwork.node.shared.infrastructure.healthcheck.LocalHealthcheck
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.selfhealth.LocalHealthMonitor
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency.CurrencySnapshotAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global.{
  GlobalSnapshotAcceptanceManager,
  GlobalSnapshotStateChannelAcceptanceManager,
  GlobalSnapshotStateChannelEventsProcessor
}
import io.constellationnetwork.node.shared.logger.LoggerBundle
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.{Hasher, HasherSelector, SecurityProvider}

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
    metagraphVersionHash: Hash,
    jarHash: Hash,
    collateral: CollateralConfig,
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]],
    environment: AppEnvironment,
    txHasher: Hasher[F],
    allowanceList: Option[Set[AllowanceListEntry]],
    metagraphId: Option[Address],
    loggerBundle: LoggerBundle[F]
  )(
    implicit globalStateProofSelector: GlobalStateProofSelector,
    currencyStateProofSelector: CurrencyStateProofSelector
  ): F[SharedServices[F, A]] =
    for {
      restartService <- RestartService.make(restartSignal, storages.cluster)

      // Shared consensus-health Ref. The writer is each layer's AbandonmentTracker (wired
      // through `ConsensusEventLoop.build`'s injectedHealthRef param); the reader is
      // `Cluster.leave()` via `consensusHealth = Some(consensusHealthRef.get)`. Created here
      // so it exists before `Cluster.make`. Layers that don't wire their consensus engine to
      // this Ref (currently: dag-l1, currency-l0, currency-l1) leave the guard reading
      // `ConsensusHealthStatus.empty` -> wedgeDetectedAtMs = None -> guard inert there.
      consensusHealthRef <- ConsensusHealthStatus.ref[F]

      // Monotonic timestamp of the most recent NodeState transition. Written by NodeStateDaemon
      // (which already observes `nodeStorage.nodeStates`), read by Cluster.leave()'s dwell-time
      // guard. Initialized to "now" at startup so the boot state has a defined entry time before
      // the first transition flows through. Wired to every layer's NodeStateDaemon.
      now <- Async[F].monotonic
      stateEntryAtRef <- Ref.of[F, FiniteDuration](now)

      // Phase A self-health throttle (docs/consensus/self-health-throttle.md). Polls JVM GC
      // pauses and OS load average; exposes `current: F[SelfHealthHint]` and Prometheus gauges
      // dag_node_self_health{state} + signal gauges. In Phase A this is observational only; in
      // Phase B (consensusSchemaVersion 15) the Facility builder reads `current` to attach the
      // hint to outgoing declarations.
      localHealthMonitor <- LocalHealthMonitor.make[F](cfg.localHealthMonitor)

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
          metagraphVersionHash,
          jarHash,
          environment,
          allowanceList,
          metagraphId,
          consensusHealth = Some(consensusHealthRef.get),
          lastStateEntryAt = Some(stateEntryAtRef.get)
        )

      localHealthcheck <- LocalHealthcheck.make[F](nodeClient, storages.cluster)
      gossip <- HasherSelector[F].withCurrent(implicit hasher => GossipImpl.make[F](queues.rumor, nodeId, generation, keyPair))
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
      updateNodeParametersAcceptanceManager = UpdateNodeParametersAcceptanceManager.make(validators.updateNodeParametersValidator)
      updateDelegatedStakeAcceptanceManager = UpdateDelegatedStakeAcceptanceManager.make(
        validators.updateDelegatedStakeValidator
      )
      updateNodeCollateralAcceptanceManager = UpdateNodeCollateralAcceptanceManager.make(
        validators.updateNodeCollateralValidator
      )
      priceStateUpdater = PriceStateUpdater.make(cfg.environment, DefaultDelegatedRewardsConfigProvider)
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
            feeCalculator,
            storages.mptStore,
            cfg.fieldsAddedOrdinals,
            cfg.environment
          ),
        updateNodeParametersAcceptanceManager,
        updateDelegatedStakeAcceptanceManager,
        updateNodeCollateralAcceptanceManager,
        validators.spendActionValidator,
        validators.pricingUpdateValidator,
        priceStateUpdater,
        collateral.amount,
        cfg.delegatedStaking.withdrawalTimeLimit.getOrElse(cfg.environment, EpochProgress.MinValue),
        storages.mptStore,
        loggerBundle
      )
      globalSnapshotContextFns = GlobalSnapshotContextFunctions.make(
        globalSnapshotAcceptanceManager,
        updateDelegatedStakeAcceptanceManager,
        cfg.delegatedStaking.withdrawalTimeLimit.getOrElse(cfg.environment, EpochProgress.MinValue),
        cfg.fieldsAddedOrdinals.tessellation3Migration.getOrElse(cfg.environment, SnapshotOrdinal.MinValue),
        cfg.fieldsAddedOrdinals.setSumFix.getOrElse(cfg.environment, SnapshotOrdinal.MinValue),
        storages.mptStore,
        cfg.incrementalDelegatedStakingStartingOrdinal.getOrElse(cfg.environment, SnapshotOrdinal.MinValue)
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
        updateNodeCollateralAcceptanceManager = updateNodeCollateralAcceptanceManager,
        priceStateUpdater = priceStateUpdater,
        consensusHealthRef = consensusHealthRef,
        stateEntryAtRef = stateEntryAtRef,
        localHealthMonitor = localHealthMonitor
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
  val updateNodeCollateralAcceptanceManager: UpdateNodeCollateralAcceptanceManager[F],
  val priceStateUpdater: PriceStateUpdater[F],
  // Exposed so each layer's consensus engine can pass it to `ConsensusEventLoop.build` as the
  // injectedHealthRef. When wired, the engine's AbandonmentTracker becomes the writer for this
  // Ref and `Cluster.leave()`'s wedge guard becomes active. Only dag-l0 wires this today.
  val consensusHealthRef: Ref[F, ConsensusHealthStatus],
  // Monotonic timestamp of the latest NodeState transition. Written by NodeStateDaemon, read by
  // Cluster.leave()'s dwell-time guard. Each layer's Daemons.make pipes this into its
  // NodeStateDaemon so transitions update the timestamp.
  val stateEntryAtRef: Ref[F, FiniteDuration],
  // Phase A self-health throttle. Owned by SharedServices because the polling fiber is a
  // single-instance background task and the hint is consumed at Facility-build time in Phase B.
  // Layers that don't need the hint just ignore this reference.
  val localHealthMonitor: LocalHealthMonitor[F]
)
