package org.tessellation.dag.l0.infrastructure.snapshot

import java.security.KeyPair

import cats.data.NonEmptySet
import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.collection.immutable.SortedMap

import org.tessellation.dag.l0.config.types.AppConfig
import org.tessellation.dag.l0.domain.snapshot.programs.{GlobalSnapshotEventCutter, SnapshotBinaryFeeCalculator}
import org.tessellation.dag.l0.infrastructure.snapshot.schema.{GlobalConsensusKind, GlobalConsensusOutcome}
import org.tessellation.json.{JsonBrotliBinarySerializer, JsonSerializer}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.cluster.services.Session
import org.tessellation.node.shared.domain.cluster.storage.ClusterStorage
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.domain.node.NodeStorage
import org.tessellation.node.shared.domain.rewards.Rewards
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.node.shared.domain.snapshot.storage.SnapshotStorage
import org.tessellation.node.shared.domain.statechannel.{FeeCalculator, FeeCalculatorConfig}
import org.tessellation.node.shared.infrastructure.block.processing.BlockAcceptanceManager
import org.tessellation.node.shared.infrastructure.consensus._
import org.tessellation.node.shared.infrastructure.metrics.Metrics
import org.tessellation.node.shared.infrastructure.snapshot.{
  GlobalSnapshotAcceptanceManager,
  GlobalSnapshotStateChannelAcceptanceManager,
  GlobalSnapshotStateChannelEventsProcessor
}
import org.tessellation.node.shared.modules.{SharedServices, SharedValidators}
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotStateProof, SnapshotOrdinal}
import org.tessellation.security.{Hasher, HasherSelector, SecurityProvider}

import eu.timepit.refined.types.numeric.NonNegLong
import org.http4s.client.Client

object GlobalSnapshotConsensus {

  def make[F[_]: Async: Random: KryoSerializer: JsonSerializer: HasherSelector: SecurityProvider: Metrics: Supervisor](
    gossip: Gossip[F],
    selfId: PeerId,
    keyPair: KeyPair,
    seedlist: Option[Set[SeedlistEntry]],
    collateral: Amount,
    clusterStorage: ClusterStorage[F],
    nodeStorage: NodeStorage[F],
    globalSnapshotStorage: SnapshotStorage[F, GlobalSnapshotArtifact, GlobalSnapshotContext],
    validators: SharedValidators[F],
    sharedServices: SharedServices[F],
    appConfig: AppConfig,
    stateChannelPullDelay: NonNegLong,
    stateChannelPurgeDelay: NonNegLong,
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]],
    feeConfigs: SortedMap[SnapshotOrdinal, FeeCalculatorConfig],
    client: Client[F],
    session: Session[F],
    rewards: Rewards[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotEvent],
    txHasher: Hasher[F]
  ): F[GlobalSnapshotConsensus[F]] =
    for {
      globalSnapshotStateChannelManager <- GlobalSnapshotStateChannelAcceptanceManager
        .make[F](stateChannelAllowanceLists, pullDelay = stateChannelPullDelay, purgeDelay = stateChannelPurgeDelay)
      jsonBrotliBinarySerializer <- JsonBrotliBinarySerializer.forSync
      feeCalculator = FeeCalculator.make(feeConfigs)
      snapshotAcceptanceManager = GlobalSnapshotAcceptanceManager.make(
        BlockAcceptanceManager.make[F](validators.blockValidator, txHasher),
        GlobalSnapshotStateChannelEventsProcessor
          .make[F](
            validators.stateChannelValidator,
            globalSnapshotStateChannelManager,
            sharedServices.currencySnapshotContextFns,
            jsonBrotliBinarySerializer,
            feeCalculator
          ),
        collateral
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
      consensusFunctions = GlobalSnapshotConsensusFunctions.make[F](
        snapshotAcceptanceManager,
        collateral,
        rewards,
        GlobalSnapshotEventCutter.make[F](
          appConfig.snapshot.consensus.eventCutter.maxBinarySizeBytes,
          SnapshotBinaryFeeCalculator.make(appConfig.shared.feeConfigs)
        )
      )
      consensusStateAdvancer = GlobalSnapshotConsensusStateAdvancer
        .make[F](keyPair, consensusStorage, globalSnapshotStorage, consensusFunctions, gossip)
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
