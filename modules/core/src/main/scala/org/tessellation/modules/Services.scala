package org.tessellation.modules

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.config.types.AppConfig
import org.tessellation.domain.cell.L0Cell
import org.tessellation.domain.dag.DAGService
import org.tessellation.domain.rewards.Rewards
import org.tessellation.domain.statechannel.StateChannelService
import org.tessellation.infrastructure.dag.DAGService
import org.tessellation.infrastructure.rewards._
import org.tessellation.infrastructure.snapshot._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.security.SecurityProvider
import org.tessellation.sdk.domain.cluster.services.{Cluster, Session}
import org.tessellation.sdk.domain.collateral.Collateral
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.healthcheck.LocalHealthcheck
import org.tessellation.sdk.infrastructure.Collateral
import org.tessellation.sdk.infrastructure.consensus.Consensus
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.sdk.modules.SdkServices

import org.http4s.client.Client

object Services {

  def make[F[_]: Async: Random: KryoSerializer: SecurityProvider: Metrics: Supervisor](
    sdkServices: SdkServices[F],
    queues: Queues[F],
    storages: Storages[F],
    validators: Validators[F],
    client: Client[F],
    session: Session[F],
    seedlist: Option[Set[PeerId]],
    selfId: PeerId,
    keyPair: KeyPair,
    cfg: AppConfig
  ): F[Services[F]] =
    for {
      rewards <- Rewards
        .make[F](
          cfg.rewards.rewardsPerEpoch,
          SoftStakingDistributor.make(cfg.rewards.softStaking),
          DTMDistributor.make(cfg.rewards.dtm),
          StardustCollectiveDistributor.make(cfg.rewards.stardust),
          RegularDistributor.make
        )
        .pure[F]
      consensus <- GlobalSnapshotConsensus
        .make[F](
          sdkServices.gossip,
          selfId,
          keyPair,
          seedlist,
          cfg.collateral.amount,
          storages.cluster,
          storages.node,
          storages.globalSnapshot,
          validators.blockValidator,
          validators.stateChannelValidator,
          cfg.snapshot,
          cfg.environment,
          client,
          session,
          rewards
        )
      dagService = DAGService.make[F](storages.globalSnapshot)
      collateralService = Collateral.make[F](cfg.collateral, storages.globalSnapshot)
      stateChannelService = StateChannelService
        .make[F](L0Cell.mkL0Cell(queues.l1Output, queues.stateChannelOutput), validators.stateChannelValidator)
    } yield
      new Services[F](
        localHealthcheck = sdkServices.localHealthcheck,
        cluster = sdkServices.cluster,
        session = sdkServices.session,
        gossip = sdkServices.gossip,
        consensus = consensus,
        dag = dagService,
        collateral = collateralService,
        rewards = rewards,
        stateChannel = stateChannelService
      ) {}
}

sealed abstract class Services[F[_]] private (
  val localHealthcheck: LocalHealthcheck[F],
  val cluster: Cluster[F],
  val session: Session[F],
  val gossip: Gossip[F],
  val consensus: Consensus[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact],
  val dag: DAGService[F],
  val collateral: Collateral[F],
  val rewards: Rewards[F],
  val stateChannel: StateChannelService[F]
)
