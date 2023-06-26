package org.tessellation.infrastructure.snapshot

import java.security.KeyPair

import cats.data.NonEmptySet
import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.block.DAGBlock
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.DAGTransaction
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotStateProof}
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.types.SnapshotConfig
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.domain.rewards.Rewards
import org.tessellation.sdk.domain.seedlist.SeedlistEntry
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.sdk.infrastructure.block.processing.BlockAcceptanceManager
import org.tessellation.sdk.infrastructure.consensus.Consensus
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.sdk.infrastructure.snapshot.{
  GlobalSnapshotAcceptanceManager,
  GlobalSnapshotStateChannelAcceptanceManager,
  GlobalSnapshotStateChannelEventsProcessor
}
import org.tessellation.sdk.modules.{SdkServices, SdkValidators}
import org.tessellation.security.SecurityProvider

import eu.timepit.refined.types.numeric.PosLong
import io.circe.disjunctionCodecs._
import org.http4s.client.Client

object GlobalSnapshotConsensus {

  def make[F[_]: Async: Random: KryoSerializer: SecurityProvider: Metrics: Supervisor](
    gossip: Gossip[F],
    selfId: PeerId,
    keyPair: KeyPair,
    seedlist: Option[Set[SeedlistEntry]],
    collateral: Amount,
    clusterStorage: ClusterStorage[F],
    nodeStorage: NodeStorage[F],
    globalSnapshotStorage: SnapshotStorage[F, GlobalSnapshotArtifact, GlobalSnapshotContext],
    validators: SdkValidators[F],
    sdkServices: SdkServices[F],
    snapshotConfig: SnapshotConfig,
    environment: AppEnvironment,
    stateChannelOrdinalDelay: Option[PosLong],
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]],
    client: Client[F],
    session: Session[F],
    rewards: Rewards[F, DAGTransaction, DAGBlock, GlobalSnapshotStateProof, GlobalIncrementalSnapshot]
  ): F[Consensus[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact, GlobalSnapshotContext]] =
    for {
      globalSnapshotStateChannelManager <- GlobalSnapshotStateChannelAcceptanceManager
        .make[F](stateChannelOrdinalDelay, stateChannelAllowanceLists)
      snapshotAcceptanceManager = GlobalSnapshotAcceptanceManager.make(
        BlockAcceptanceManager.make[F, DAGTransaction, DAGBlock](validators.blockValidator),
        GlobalSnapshotStateChannelEventsProcessor
          .make[F](validators.stateChannelValidator, globalSnapshotStateChannelManager, sdkServices.currencySnapshotContextFns),
        collateral
      )
      consensus <- Consensus.make[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact, GlobalSnapshotContext](
        GlobalSnapshotConsensusFunctions.make[F](
          globalSnapshotStorage,
          snapshotAcceptanceManager,
          collateral,
          rewards,
          environment
        ),
        gossip,
        selfId,
        keyPair,
        snapshotConfig.consensus,
        seedlist,
        clusterStorage,
        nodeStorage,
        client,
        session
      )
    } yield consensus
}
