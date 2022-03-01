package org.tessellation.modules

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.config.types.AppConfig
import org.tessellation.domain.aci.StateChannelRunner
import org.tessellation.infrastructure.aci.StateChannelRunner
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

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    sdkServices: SdkServices[F],
    queues: Queues[F],
    storages: Storages[F],
    selfId: PeerId,
    keyPair: KeyPair,
    cfg: AppConfig
  ): F[Services[F]] =
    for {
      metrics <- Metrics.make[F]
      stateChannelRunner <- StateChannelRunner.make[F](queues.stateChannelOutput)
      consensus <- GlobalSnapshotConsensus
        .make[F](sdkServices.gossip, selfId, keyPair, storages.cluster, storages.globalSnapshot, cfg.snapshot)
    } yield
      new Services[F](
        cluster = sdkServices.cluster,
        session = sdkServices.session,
        metrics = metrics,
        gossip = sdkServices.gossip,
        stateChannelRunner = stateChannelRunner,
        consensus = consensus
      ) {}
}

sealed abstract class Services[F[_]] private (
  val cluster: Cluster[F],
  val session: Session[F],
  val metrics: Metrics[F],
  val gossip: Gossip[F],
  val stateChannelRunner: StateChannelRunner[F],
  val consensus: Consensus[F, GlobalSnapshotKey, GlobalSnapshotArtifact]
)
