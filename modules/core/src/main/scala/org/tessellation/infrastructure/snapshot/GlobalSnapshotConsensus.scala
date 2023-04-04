package org.tessellation.infrastructure.snapshot

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}

import org.tessellation.domain.rewards.Rewards
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.types.SnapshotConfig
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.sdk.infrastructure.consensus.Consensus
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.sdk.infrastructure.snapshot.{GlobalSnapshotAcceptanceManager, GlobalSnapshotContextFunctions}
import org.tessellation.security.SecurityProvider

import io.circe.disjunctionCodecs._
import org.http4s.client.Client
import org.tessellation.sdk.domain.block.processing.BlockValidator
import org.tessellation.sdk.domain.statechannel.StateChannelValidator
import org.tessellation.schema.transaction.DAGTransaction
import org.tessellation.schema.block.DAGBlock
import org.tessellation.sdk.domain.consensus.ArtifactService
import org.tessellation.sdk.infrastructure.block.processing.BlockAcceptanceManager
import org.tessellation.sdk.infrastructure.snapshot.GlobalSnapshotStateChannelEventsProcessor

object GlobalSnapshotConsensus {

  def make[F[_]: Async: Random: KryoSerializer: SecurityProvider: Metrics: Supervisor](
    gossip: Gossip[F],
    selfId: PeerId,
    keyPair: KeyPair,
    seedlist: Option[Set[PeerId]],
    collateral: Amount,
    clusterStorage: ClusterStorage[F],
    nodeStorage: NodeStorage[F],
    blockValidator: BlockValidator[F, DAGTransaction, DAGBlock],
    stateChannelValidator: StateChannelValidator[F],
    snapshotConfig: SnapshotConfig,
    environment: AppEnvironment,
    client: Client[F],
    session: Session[F],
    rewards: Rewards[F],
    snapshotService: ArtifactService[F, GlobalSnapshotArtifact, GlobalSnapshotContext]
  ): F[Consensus[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact, GlobalSnapshotContext]] = {
    val currencySnapshotConsensusFunctions = CurrencySnapshotConsensusFunctions.make[F](
      BlockAcceptanceManager.make[F, CurrencyTransaction, CurrencyBlock]
    )
    val globalSnapshotConsensusFunctions = GlobalSnapshotConsensusFunctions.make[F](
      BlockAcceptanceManager.make[F, DAGTransaction, DAGBlock](blockValidator),
      GlobalSnapshotStateChannelEventsProcessor.make[F](stateChannelValidator, currencySnapshotConsensusFunctions),
      collateral,
      rewards,
      environment
    )
    val globalSnapshotConsensusValidator = SnapshotConsensusValidator.make[F](globalSnapshotConsensusFunctions)
    Consensus.make[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact, GlobalSnapshotContext](
      snapshotService,
      globalSnapshotConsensusFunctions,
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
  }

}
