package org.tessellation.modules

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.effect.std.Random
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.config.types.AppConfig
import org.tessellation.domain.dag.DAGService
import org.tessellation.domain.rewards.Rewards
import org.tessellation.infrastructure.dag.DAGService
import org.tessellation.infrastructure.rewards._
import org.tessellation.infrastructure.snapshot._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.cluster.services.{Cluster, Session}
import org.tessellation.sdk.domain.collateral.Collateral
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.infrastructure.Collateral
import org.tessellation.sdk.infrastructure.consensus.Consensus
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.sdk.modules.SdkServices
import org.tessellation.security.SecurityProvider

import org.http4s.client.Client

object Services {

  def make[F[_]: Async: Random: KryoSerializer: SecurityProvider: Metrics](
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
          cfg.rewards,
          cfg.snapshot.timeTriggerInterval,
          SoftStaking.make(cfg.rewards.softStaking),
          DTM.make(cfg.rewards.dtm, cfg.snapshot.timeTriggerInterval),
          StardustCollective.make(cfg.rewards.stardust)
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
          cfg.healthCheck,
          cfg.snapshot,
          client,
          session,
          rewards
        )
      dagService = DAGService.make[F](storages.globalSnapshot)
      collateralService = Collateral.make[F](cfg.collateral, storages.globalSnapshot)
    } yield
      new Services[F](
        cluster = sdkServices.cluster,
        session = sdkServices.session,
        gossip = sdkServices.gossip,
        consensus = consensus,
        dag = dagService,
        collateral = collateralService,
        rewards = rewards
      ) {}
}

sealed abstract class Services[F[_]] private (
  val cluster: Cluster[F],
  val session: Session[F],
  val gossip: Gossip[F],
  val consensus: Consensus[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact],
  val dag: DAGService[F],
  val collateral: Collateral[F],
  val rewards: Rewards[F]
)
