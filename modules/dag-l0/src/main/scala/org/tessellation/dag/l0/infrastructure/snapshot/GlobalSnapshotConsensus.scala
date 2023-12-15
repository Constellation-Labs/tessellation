package org.tessellation.dag.l0.infrastructure.snapshot

import java.security.KeyPair

import cats.data.NonEmptySet
import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.dag.l0.domain.snapshot.programs.GlobalSnapshotEventCutter
import org.tessellation.json.JsonBrotliBinarySerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.config.types.SnapshotConfig
import org.tessellation.node.shared.domain.cluster.services.Session
import org.tessellation.node.shared.domain.cluster.storage.ClusterStorage
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.domain.node.NodeStorage
import org.tessellation.node.shared.domain.rewards.Rewards
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.node.shared.domain.snapshot.storage.SnapshotStorage
import org.tessellation.node.shared.infrastructure.block.processing.BlockAcceptanceManager
import org.tessellation.node.shared.infrastructure.consensus.Consensus
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
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotStateProof}
import org.tessellation.security.{Hasher, SecurityProvider}

import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.disjunctionCodecs._
import org.http4s.client.Client

object GlobalSnapshotConsensus {

  def make[F[_]: Async: Random: KryoSerializer: Hasher: SecurityProvider: Metrics: Supervisor](
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
    snapshotConfig: SnapshotConfig,
    stateChannelPullDelay: NonNegLong,
    stateChannelPurgeDelay: NonNegLong,
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]],
    client: Client[F],
    session: Session[F],
    rewards: Rewards[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotEvent]
  ): F[Consensus[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact, GlobalSnapshotContext]] =
    for {
      globalSnapshotStateChannelManager <- GlobalSnapshotStateChannelAcceptanceManager
        .make[F](stateChannelAllowanceLists, pullDelay = stateChannelPullDelay, purgeDelay = stateChannelPurgeDelay)
      jsonBrotliBinarySerializer <- JsonBrotliBinarySerializer.forSync
      snapshotAcceptanceManager = GlobalSnapshotAcceptanceManager.make(
        BlockAcceptanceManager.make[F](validators.blockValidator),
        GlobalSnapshotStateChannelEventsProcessor
          .make[F](
            validators.stateChannelValidator,
            globalSnapshotStateChannelManager,
            sharedServices.currencySnapshotContextFns,
            jsonBrotliBinarySerializer
          ),
        collateral
      )
      consensus <- Consensus.make[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact, GlobalSnapshotContext](
        GlobalSnapshotConsensusFunctions.make[F](
          globalSnapshotStorage,
          snapshotAcceptanceManager,
          collateral,
          rewards,
          gossip,
          GlobalSnapshotEventCutter.make[F](snapshotConfig.consensus.eventCutter.maxBinarySizeBytes)
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
