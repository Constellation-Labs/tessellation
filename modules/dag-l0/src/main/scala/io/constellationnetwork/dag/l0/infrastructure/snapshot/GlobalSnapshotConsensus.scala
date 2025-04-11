package io.constellationnetwork.dag.l0.infrastructure.snapshot

import java.security.KeyPair

import cats.Parallel
import cats.data.NonEmptySet
import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.dag.l0.config.DefaultDelegatedRewardsConfigProvider
import io.constellationnetwork.dag.l0.config.types.AppConfig
import io.constellationnetwork.dag.l0.domain.snapshot.programs.{
  GlobalSnapshotEventCutter,
  SnapshotBinaryFeeCalculator,
  UpdateNodeParametersCutter
}
import io.constellationnetwork.dag.l0.infrastructure.rewards._
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event._
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.{GlobalConsensusKind, GlobalConsensusOutcome}
import io.constellationnetwork.json.{JsonBrotliBinarySerializer, JsonSerializer}
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.config.types.SharedConfig
import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.domain.seedlist.SeedlistEntry
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.domain.snapshot.storage.SnapshotStorage
import io.constellationnetwork.node.shared.domain.statechannel.{FeeCalculator, FeeCalculatorConfig}
import io.constellationnetwork.node.shared.domain.swap.block.AllowSpendBlockAcceptanceManager
import io.constellationnetwork.node.shared.domain.tokenlock.block.TokenLockBlockAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.block.processing.BlockAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.node.shared.modules.{SharedServices, SharedValidators}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotStateProof, SnapshotOrdinal}
import io.constellationnetwork.security._

import eu.timepit.refined.types.numeric.NonNegLong
import org.http4s.client.Client

object GlobalSnapshotConsensus {

  def make[F[
    _
  ]: Async: Parallel: Random: KryoSerializer: JsonSerializer: HasherSelector: SecurityProvider: Metrics: Supervisor, R <: CliMethod](
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
    classicRewards: Rewards[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotEvent],
    delegatorRewards: DelegatedRewardsDistributor[F],
    txHasher: Hasher[F],
    restartService: RestartService[F, R],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
  ): F[GlobalSnapshotConsensus[F]] =
    for {
      globalSnapshotStateChannelManager <- GlobalSnapshotStateChannelAcceptanceManager
        .make[F](stateChannelAllowanceLists, pullDelay = stateChannelPullDelay, purgeDelay = stateChannelPurgeDelay)
      jsonBrotliBinarySerializer <- JsonBrotliBinarySerializer.forSync
      feeCalculator = FeeCalculator.make(feeConfigs)
      snapshotAcceptanceManager = GlobalSnapshotAcceptanceManager.make(
        sharedCfg.fieldsAddedOrdinals.tessellation3Migration.getOrElse(sharedCfg.environment, SnapshotOrdinal.MinValue),
        BlockAcceptanceManager.make[F](validators.blockValidator, txHasher),
        AllowSpendBlockAcceptanceManager.make[F](validators.allowSpendBlockValidator),
        TokenLockBlockAcceptanceManager.make[F](validators.tokenLockBlockValidator),
        GlobalSnapshotStateChannelEventsProcessor
          .make[F](
            validators.stateChannelValidator,
            globalSnapshotStateChannelManager,
            sharedServices.currencySnapshotContextFns,
            jsonBrotliBinarySerializer,
            feeCalculator
          ),
        sharedServices.updateNodeParametersAcceptanceManager,
        sharedServices.updateDelegatedStakeAcceptanceManager,
        sharedServices.updateNodeCollateralAcceptanceManager,
        validators.spendActionValidator,
        collateral,
        sharedCfg.delegatedStaking.withdrawalTimeLimit.getOrElse(sharedCfg.environment, EpochProgress.MinValue)
      )
      consensusStorage <- ConsensusStorage
        .make[
          F,
          GlobalSnapshotEvent,
          GlobalSnapshotKey,
          GlobalSnapshotArtifact,
          GlobalSnapshotContext,
          GlobalSnapshotStatus,
          GlobalConsensusOutcome,
          GlobalConsensusKind
        ](
          appConfig.snapshot.consensus
        )
      updateNodeParametersCutter = UpdateNodeParametersCutter.make(appConfig.snapshot.consensus.eventCutter.maxUpdateNodeParametersSize)

      consensusFunctions = GlobalSnapshotConsensusFunctions.make[F](
        snapshotAcceptanceManager,
        collateral,
        classicRewards,
        delegatorRewards,
        GlobalSnapshotEventCutter.make[F](
          appConfig.snapshot.consensus.eventCutter.maxBinarySizeBytes,
          SnapshotBinaryFeeCalculator.make(appConfig.shared.feeConfigs)
        ),
        updateNodeParametersCutter,
        appConfig.environment,
        DefaultDelegatedRewardsConfigProvider,
        sharedCfg.fieldsAddedOrdinals.tessellation3Migration.getOrElse(sharedCfg.environment, SnapshotOrdinal.MinValue)
      )

      consensusStateAdvancer = GlobalSnapshotConsensusStateAdvancer
        .make[F](
          sharedCfg.lastGlobalSnapshotsSync,
          keyPair,
          consensusStorage,
          globalSnapshotStorage,
          consensusFunctions,
          gossip,
          restartService,
          nodeStorage,
          appConfig.shared.leavingDelay,
          getGlobalSnapshotByOrdinal
        )
      consensusStateCreator = GlobalSnapshotConsensusStateCreator.make[F](consensusFunctions, consensusStorage, gossip, selfId, seedlist)
      consensusStateRemover = GlobalSnapshotConsensusStateRemover.make[F](consensusStorage, gossip)
      consensusStatusOps = GlobalSnapshotConsensusOps.make
      stateUpdater = ConsensusStateUpdater.make(
        consensusStateAdvancer,
        consensusStorage,
        gossip,
        consensusStatusOps
      )
      consensusClient = ConsensusClient.make[F, GlobalSnapshotKey, GlobalConsensusOutcome](client, session)
      manager <- ConsensusManager.make(
        appConfig.snapshot.consensus,
        consensusStorage,
        consensusStateCreator,
        stateUpdater,
        consensusStateAdvancer,
        consensusStateRemover,
        consensusStatusOps,
        nodeStorage,
        clusterStorage,
        consensusClient
      )
      routes = new ConsensusRoutes(consensusStorage)
      handler = GlobalConsensusHandler.make(consensusStorage, manager, consensusFunctions)
      consensus = new Consensus(handler, consensusStorage, manager, routes, consensusFunctions)
    } yield consensus
}
