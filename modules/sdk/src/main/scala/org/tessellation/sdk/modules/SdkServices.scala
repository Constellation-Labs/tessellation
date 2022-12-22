package org.tessellation.sdk.modules

import java.security.KeyPair

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.generation.Generation
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.security.SecurityProvider
import org.tessellation.schema.security.hash.Hash
import org.tessellation.sdk.config.types.SdkConfig
import org.tessellation.sdk.domain.cluster.services.{Cluster, Session}
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.healthcheck.LocalHealthcheck
import org.tessellation.sdk.http.p2p.clients.NodeClient
import org.tessellation.sdk.infrastructure.cluster.services.Cluster
import org.tessellation.sdk.infrastructure.gossip.Gossip
import org.tessellation.sdk.infrastructure.healthcheck.LocalHealthcheck
import org.tessellation.sdk.infrastructure.metrics.Metrics

import fs2.concurrent.SignallingRef

object SdkServices {

  def make[F[_]: Async: KryoSerializer: SecurityProvider: Metrics: Supervisor](
    cfg: SdkConfig,
    nodeId: PeerId,
    generation: Generation,
    keyPair: KeyPair,
    storages: SdkStorages[F],
    queues: SdkQueues[F],
    session: Session[F],
    nodeClient: NodeClient[F],
    seedlist: Option[Set[PeerId]],
    restartSignal: SignallingRef[F, Unit],
    versionHash: Hash
  ): F[SdkServices[F]] = {

    val cluster = Cluster
      .make[F](
        cfg.leavingDelay,
        cfg.httpConfig,
        nodeId,
        keyPair,
        storages.cluster,
        storages.session,
        storages.node,
        seedlist,
        restartSignal,
        versionHash
      )

    for {
      localHealthcheck <- LocalHealthcheck.make[F](nodeClient, storages.cluster)
      gossip <- Gossip.make[F](queues.rumor, nodeId, generation, keyPair)
    } yield
      new SdkServices[F](
        localHealthcheck = localHealthcheck,
        cluster = cluster,
        session = session,
        gossip = gossip
      ) {}
  }
}

sealed abstract class SdkServices[F[_]] private (
  val localHealthcheck: LocalHealthcheck[F],
  val cluster: Cluster[F],
  val session: Session[F],
  val gossip: Gossip[F]
)
