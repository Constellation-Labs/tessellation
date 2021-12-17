package org.tessellation.modules

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.config.types.AppConfig
import org.tessellation.domain.aci.StateChannelRunner
import org.tessellation.domain.cluster.services.{Cluster, Session}
import org.tessellation.domain.gossip.Gossip
import org.tessellation.domain.healthcheck.HealthCheck
import org.tessellation.infrastructure.aci.StateChannelRunner
import org.tessellation.infrastructure.cluster.services.{Cluster, Session}
import org.tessellation.infrastructure.gossip.Gossip
import org.tessellation.infrastructure.healthcheck.HealthCheck
import org.tessellation.infrastructure.metrics.Metrics
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.SecurityProvider

object Services {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    cfg: AppConfig,
    nodeId: PeerId,
    keyPair: KeyPair,
    storages: Storages[F],
    queues: Queues[F]
  ): F[Services[F]] =
    for {
      metrics <- Metrics.make[F]
      healthcheck = HealthCheck.make[F]
      session = Session.make[F](storages.session, storages.node)
      cluster = Cluster
        .make[F](cfg, nodeId, keyPair, storages.session)
      gossip <- Gossip.make[F](queues.rumor, nodeId, keyPair)
      stateChannelRunner <- StateChannelRunner.make[F](queues.stateChannelOutput)
    } yield
      new Services[F](
        healthcheck = healthcheck,
        cluster = cluster,
        session = session,
        metrics = metrics,
        gossip = gossip,
        stateChannelRunner = stateChannelRunner
      ) {}
}

sealed abstract class Services[F[_]] private (
  val healthcheck: HealthCheck[F],
  val cluster: Cluster[F],
  val session: Session[F],
  val metrics: Metrics[F],
  val gossip: Gossip[F],
  val stateChannelRunner: StateChannelRunner[F]
)
