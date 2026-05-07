package io.constellationnetwork.dag.l0.infrastructure.snapshot

import java.security.KeyPair

import cats.Parallel
import cats.data.NonEmptySet
import cats.effect.kernel.{Async, Fiber}
import cats.effect.std.{Queue, Random, Supervisor}
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.dag.l0.config.types.AppConfig
import io.constellationnetwork.dag.l0.domain.snapshot.programs.{
  GlobalSnapshotEventCutter,
  SnapshotBinaryFeeCalculator,
  UpdateNodeParametersCutter
}
import io.constellationnetwork.dag.l0.infrastructure.rewards.RewardsService
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event._
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.{GlobalConsensusKind, GlobalConsensusOutcome}
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.json.{JsonBrotliBinarySerializer, JsonSerializer}
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.config.DefaultDelegatedRewardsConfigProvider
import io.constellationnetwork.node.shared.config.types.SharedConfig
import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.node.shared.domain.statechannel.{FeeCalculator, FeeCalculatorConfig}
import io.constellationnetwork.node.shared.domain.swap.block.AllowSpendBlockAcceptanceManager
import io.constellationnetwork.node.shared.domain.tokenlock.block.TokenLockBlockAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.block.processing.BlockAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.{ConsensusCommand, ConsensusEventLoop, _}
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusPeerDeclaration
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.gossip.RumorHandler
import io.constellationnetwork.node.shared.infrastructure.gossip.event.EventGossipClient
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global.{
  GlobalSnapshotAcceptanceManager,
  GlobalSnapshotStateChannelAcceptanceManager,
  GlobalSnapshotStateChannelEventsProcessor
}
import io.constellationnetwork.node.shared.logger.LoggerBundle
import io.constellationnetwork.node.shared.modules.{SharedServices, SharedValidators}
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.gossip.RumorRaw
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security._

import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.Json
import org.http4s.client.Client

/** Factory for creating the Global L0 consensus engine.
  *
  * Wires together all components and starts the consensus background stream. Returns a Consensus instance with handler (for gossip),
  * manager (external API), storage (state queries), and routes (HTTP endpoints).
  *
  * @see
  *   ConsensusEventLoop for FSM and command processing
  */
object GlobalSnapshotConsensus {

  def make[F[_]: Async: Parallel: Random: JsonSerializer: HasherSelector: SecurityProvider: Metrics, R <: CliMethod](
    sharedCfg: SharedConfig,
    gossip: Gossip[F],
    selfId: PeerId,
    keyPair: KeyPair,
    seedlist: Option[Set[SeedlistEntry]],
    collateral: Amount,
    clusterStorage: ClusterStorage[F],
    nodeStorage: NodeStorage[F],
    globalSnapshotStorage: SnapshotStorage[F, GlobalSnapshotArtifact, GlobalSnapshotContext],
    validators: SharedValidators[F],
    sharedServices: SharedServices[F, R],
    appConfig: AppConfig,
    stateChannelPullDelay: NonNegLong,
    stateChannelPurgeDelay: NonNegLong,
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]],
    feeConfigs: SortedMap[SnapshotOrdinal, FeeCalculatorConfig],
    client: Client[F],
    session: Session[F],
    rewardsService: RewardsService[F],
    txHasher: Hasher[F],
    restartService: RestartService[F, R],
    lastNGlobalSnapshotStorage: LastNGlobalSnapshotStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    mptStore: MptStore[F, GlobalStateKey],
    eventMempool: EventMempool[F, GlobalSnapshotEvent, GlobalStateKey],
    eventGossipClient: EventGossipClient[F, GlobalSnapshotEvent],
    loggerBundle: LoggerBundle[F],
    rumorQueue: Queue[F, Hashed[RumorRaw]],
    // B2 witness channel: probation-peer chain tips from the mesh gossip layer. See
    // StallDetector.maybeEmitAdmissionVotes for usage. Injected as a thunk because
    // EventGossipDaemon is constructed after consensus in Main.scala — the Ref pattern in
    // Main.scala populates the real getter once the daemon is up; before that it returns
    // Map.empty and no admission votes fire (safe default).
    getPeerChainTips: F[Map[PeerId, io.constellationnetwork.node.shared.infrastructure.gossip.event.ChainTip]]
  )(implicit supervisor: Supervisor[F], globalStateProofSelector: GlobalStateProofSelector): F[GlobalSnapshotConsensus[F]] =
    for {
      globalStateChannelManager <- GlobalSnapshotStateChannelAcceptanceManager
        .make[F](stateChannelAllowanceLists, pullDelay = stateChannelPullDelay, purgeDelay = stateChannelPurgeDelay)

      feeCalculator = FeeCalculator.make(feeConfigs)

      snapshotAcceptanceManager =
        GlobalSnapshotAcceptanceManager.make(
          sharedCfg.fieldsAddedOrdinals,
          sharedCfg.metagraphsSync,
          sharedCfg.environment,
          BlockAcceptanceManager.make[F](validators.blockValidator, txHasher),
          AllowSpendBlockAcceptanceManager.make[F](validators.allowSpendBlockValidator),
          TokenLockBlockAcceptanceManager.make[F](validators.tokenLockBlockValidator),
          GlobalSnapshotStateChannelEventsProcessor.make[F](
            validators.stateChannelValidator,
            globalStateChannelManager,
            sharedServices.currencySnapshotContextFns,
            feeCalculator,
            mptStore
          ),
          sharedServices.updateNodeParametersAcceptanceManager,
          sharedServices.updateDelegatedStakeAcceptanceManager,
          sharedServices.updateNodeCollateralAcceptanceManager,
          validators.spendActionValidator,
          validators.pricingUpdateValidator,
          sharedServices.priceStateUpdater,
          collateral,
          sharedCfg.delegatedStaking.withdrawalTimeLimit
            .getOrElse(sharedCfg.environment, EpochProgress.MinValue),
          mptStore,
          loggerBundle
        )

      consensusStorage <- ConsensusStorage.make[
        F,
        GlobalSnapshotEvent,
        GlobalSnapshotKey,
        GlobalSnapshotArtifact,
        GlobalSnapshotContext,
        GlobalSnapshotStatus,
        GlobalConsensusOutcome,
        GlobalConsensusKind
      ](appConfig.snapshot.consensus)

      consensusFunctions =
        GlobalSnapshotConsensusFunctions.make[F](
          snapshotAcceptanceManager,
          collateral,
          rewardsService,
          GlobalSnapshotEventCutter.make(
            appConfig.snapshot.consensus.eventCutter.maxBinarySizeBytes,
            SnapshotBinaryFeeCalculator.make(appConfig.shared.feeConfigs, mptStore)
          ),
          UpdateNodeParametersCutter.make(appConfig.snapshot.consensus.eventCutter.maxUpdateNodeParametersSize),
          appConfig.environment,
          DefaultDelegatedRewardsConfigProvider,
          sharedCfg.fieldsAddedOrdinals.tessellation3Migration
            .getOrElse(sharedCfg.environment, SnapshotOrdinal.MinValue),
          sharedCfg.fieldsAddedOrdinals.setSumFix
            .getOrElse(sharedCfg.environment, SnapshotOrdinal.MinValue),
          sharedCfg.incrementalDelegatedStakingStartingOrdinal
            .getOrElse(sharedCfg.environment, SnapshotOrdinal.MinValue),
          mptStore
        )

      stateAdvancer =
        GlobalSnapshotConsensusStateAdvancer.make(
          appConfig.snapshot.consensus,
          keyPair,
          consensusStorage,
          globalSnapshotStorage,
          consensusFunctions,
          gossip,
          restartService,
          nodeStorage,
          appConfig.shared.leavingDelay,
          lastNGlobalSnapshotStorage,
          lastGlobalSnapshotStorage,
          getGlobalSnapshotByOrdinal,
          clusterStorage,
          eventMempool,
          eventGossipClient,
          loggerBundle,
          mptStore
        )

      facilitatorSelector = FacilitatorSelector.make(
        appConfig.snapshot.maxFacilitatorCount.get(appConfig.environment).map(_.value)
      )

      peerQualityTracker <- PeerQualityTracker.make[F]

      tcaFilter = TrailingCommonAncestorFilter.make[F]

      stateCreator =
        GlobalSnapshotConsensusStateCreator.make(
          consensusFunctions,
          consensusStorage,
          gossip,
          selfId,
          seedlist,
          facilitatorSelector,
          appConfig.snapshot.consensus.deterministicConfigHash,
          appConfig.snapshot.consensus,
          peerQualityTracker,
          tcaFilter,
          eventMempool
        )

      stateRemover =
        GlobalSnapshotConsensusStateRemover.make(
          consensusStorage,
          gossip
        )

      consensusOps = GlobalSnapshotConsensusOps.make

      stateUpdater =
        ConsensusStateUpdater.make(
          stateAdvancer,
          consensusStorage,
          consensusOps
        )

      consensusClient = ConsensusClient.make[F, GlobalSnapshotKey, GlobalConsensusOutcome](client, session)

      directPushFn = ConsensusDirectSender.makeDirectPushFn(clusterStorage, consensusClient)
      _ <- gossip.setDirectPushFn(directPushFn)

      viewChangeVoter = new GossipingViewChangeVoter[
        F,
        GlobalSnapshotEvent,
        GlobalSnapshotKey,
        GlobalSnapshotArtifact,
        GlobalSnapshotContext,
        GlobalSnapshotStatus,
        GlobalConsensusOutcome,
        GlobalConsensusKind
      ](
        selfId,
        keyPair,
        gossip,
        consensusStorage,
        (o: GlobalConsensusOutcome) => o.finished.snapshotHash,
        org.typelevel.log4cats.slf4j.Slf4jLogger.getLogger[F]
      )

      evictionVoter = new GossipingEvictionVoter[
        F,
        GlobalSnapshotEvent,
        GlobalSnapshotKey,
        GlobalSnapshotArtifact,
        GlobalSnapshotContext,
        GlobalSnapshotStatus,
        GlobalConsensusOutcome,
        GlobalConsensusKind
      ](
        selfId,
        keyPair,
        gossip,
        consensusStorage,
        (o: GlobalConsensusOutcome) => o.finished.snapshotHash,
        org.typelevel.log4cats.slf4j.Slf4jLogger.getLogger[F]
      )

      admissionVoter = new io.constellationnetwork.node.shared.infrastructure.consensus.engine.GossipingAdmissionVoter[
        F,
        GlobalSnapshotEvent,
        GlobalSnapshotKey,
        GlobalSnapshotArtifact,
        GlobalSnapshotContext,
        GlobalSnapshotStatus,
        GlobalConsensusOutcome,
        GlobalConsensusKind
      ](
        selfId,
        keyPair,
        gossip,
        consensusStorage,
        (o: GlobalConsensusOutcome) => o.finished.snapshotHash,
        org.typelevel.log4cats.slf4j.Slf4jLogger.getLogger[F]
      )

      evictionCertificateGossiper =
        new io.constellationnetwork.node.shared.infrastructure.consensus.engine.GossipingEvictionCertificateGossiper[
          F,
          GlobalSnapshotEvent,
          GlobalSnapshotKey,
          GlobalSnapshotArtifact,
          GlobalSnapshotContext,
          GlobalSnapshotStatus,
          GlobalConsensusOutcome,
          GlobalConsensusKind
        ](selfId, gossip, consensusStorage, org.typelevel.log4cats.slf4j.Slf4jLogger.getLogger[F])

      loop <-
        ConsensusEventLoop.build[
          F,
          GlobalSnapshotEvent,
          GlobalSnapshotKey,
          GlobalSnapshotArtifact,
          GlobalSnapshotContext,
          GlobalSnapshotStatus,
          GlobalConsensusOutcome,
          GlobalConsensusKind
        ](
          selfId,
          consensusStorage,
          stateCreator,
          stateUpdater,
          stateAdvancer,
          stateRemover,
          consensusOps,
          nodeStorage,
          clusterStorage,
          consensusFunctions,
          consensusClient,
          appConfig.snapshot.consensus,
          facilitatorSelector,
          peerQualityTracker,
          viewChangeVoter,
          evictionVoter,
          admissionVoter,
          evictionCertificateGossiper,
          (o: GlobalConsensusOutcome) =>
            !o.recentProofSizes.values.exists(_ >= appConfig.snapshot.consensus.bootstrapCompleteProofsThreshold),
          (o: GlobalConsensusOutcome) => o.readmissionCountdown.filter(_._2 > 0).keySet,
          (o: GlobalConsensusOutcome) => o.finished.snapshotHash,
          getPeerChainTips
        )

      handler = GlobalConsensusHandler.make(loop.queue)

      routes = new ConsensusRoutes[
        F,
        GlobalSnapshotKey,
        GlobalSnapshotArtifact,
        GlobalSnapshotContext,
        GlobalSnapshotStatus,
        GlobalConsensusOutcome,
        GlobalConsensusKind
      ](consensusStorage, rumorQueue)

      triggerEvent = loop.queue.offer(ConsensusCommand.FacilitateByEvent)

      _ <- supervisor.supervise(loop.run.compile.drain)
      consensus = new Consensus(
        handler,
        consensusStorage,
        loop.manager,
        routes,
        consensusFunctions,
        Some(loop.healthRef),
        Some(triggerEvent)
      )
    } yield consensus
}
