package org.tessellation.sdk.modules

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.syntax.functor._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.types.SdkConfig
import org.tessellation.sdk.domain.cluster.services.{Cluster, Session}
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.healthcheck.HealthCheck
import org.tessellation.sdk.infrastructure.cluster.services.Cluster
import org.tessellation.sdk.infrastructure.gossip.Gossip
import org.tessellation.sdk.infrastructure.healthcheck.HealthCheck
import org.tessellation.security.SecurityProvider

import fs2.concurrent.SignallingRef

object SdkServices {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    cfg: SdkConfig,
    nodeId: PeerId,
    keyPair: KeyPair,
    storages: SdkStorages[F],
    queues: SdkQueues[F],
    session: Session[F],
    restartSignal: SignallingRef[F, Unit]
  ): F[SdkServices[F]] = {
    val cluster = Cluster
      .make[F](cfg.leavingDelay, cfg.httpConfig, nodeId, keyPair, storages.session, storages.node, restartSignal)
    val healthCheck = HealthCheck.make[F]

    for {
      gossip <- Gossip.make[F](queues.rumor, nodeId, keyPair)
    } yield
      new SdkServices[F](
        cluster = cluster,
        session = session,
        gossip = gossip,
        healthCheck = healthCheck
      ) {}
  }
}

sealed abstract class SdkServices[F[_]] private (
  val cluster: Cluster[F],
  val session: Session[F],
  val gossip: Gossip[F],
  val healthCheck: HealthCheck[F]
)
