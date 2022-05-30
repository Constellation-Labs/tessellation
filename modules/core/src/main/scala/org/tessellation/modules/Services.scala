package org.tessellation.modules

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.effect.std.Random
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.config.types.AppConfig
import org.tessellation.domain.dag.DAGService
import org.tessellation.infrastructure.dag.DAGService
import org.tessellation.infrastructure.metrics.Metrics
import org.tessellation.infrastructure.snapshot._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.cluster.services.{Cluster, Session}
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.infrastructure.consensus.Consensus
import org.tessellation.sdk.modules.SdkServices
import org.tessellation.security.SecurityProvider

object Services {

  def make[F[_]: Async: Random: KryoSerializer: SecurityProvider](
    sdkServices: SdkServices[F],
    queues: Queues[F],
    storages: Storages[F],
    validators: Validators[F],
    whitelisting: Option[Set[PeerId]],
    selfId: PeerId,
    keyPair: KeyPair,
    cfg: AppConfig
  ): F[Services[F]] =
    for {
      metrics <- Metrics.make[F]
      consensus <- GlobalSnapshotConsensus
        .make[F](
          sdkServices.gossip,
          selfId,
          keyPair,
          whitelisting,
          storages.cluster,
          storages.globalSnapshot,
          validators.blockValidator,
          cfg.healthCheck,
          cfg.snapshot
        )
      dagService = DAGService.make[F](storages.globalSnapshot)
    } yield
      new Services[F](
        cluster = sdkServices.cluster,
        session = sdkServices.session,
        metrics = metrics,
        gossip = sdkServices.gossip,
        consensus = consensus,
        dag = dagService
      ) {}
}

sealed abstract class Services[F[_]] private (
  val cluster: Cluster[F],
  val session: Session[F],
  val metrics: Metrics[F],
  val gossip: Gossip[F],
  val consensus: Consensus[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact],
  val dag: DAGService[F]
)
