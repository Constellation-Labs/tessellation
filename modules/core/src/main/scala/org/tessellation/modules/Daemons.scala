package org.tessellation.modules

import cats.Parallel
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.config.types.AppConfig
import org.tessellation.http.p2p.P2PClient
import org.tessellation.infrastructure.healthcheck.HealthCheckDaemon
import org.tessellation.infrastructure.snapshot.GlobalSnapshotEventsPublisherDaemon
import org.tessellation.infrastructure.snapshot.daemon.DownloadDaemon
import org.tessellation.infrastructure.trust.TrustDaemon
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.Daemon
import org.tessellation.sdk.infrastructure.cluster.daemon.NodeStateDaemon
import org.tessellation.sdk.infrastructure.gossip.{GossipDaemon, RumorHandler}
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.security.SecurityProvider

object Daemons {

  def start[F[_]: Async: SecurityProvider: KryoSerializer: Random: Parallel: Metrics](
    storages: Storages[F],
    services: Services[F],
    programs: Programs[F],
    queues: Queues[F],
    healthChecks: HealthChecks[F],
    validators: Validators[F],
    p2pClient: P2PClient[F],
    handler: RumorHandler[F],
    nodeId: PeerId,
    cfg: AppConfig
  ): F[Unit] =
    List[Daemon[F]](
      GossipDaemon
        .make[F](
          storages.rumor,
          queues.rumor,
          storages.cluster,
          p2pClient.gossip,
          handler,
          nodeId,
          cfg.gossip.daemon,
          healthChecks.ping,
          services.collateral
        ),
      NodeStateDaemon.make(storages.node, services.gossip),
      DownloadDaemon.make(storages.node, programs.download),
      TrustDaemon.make(cfg.trust.daemon, storages.trust, nodeId),
      HealthCheckDaemon.make(healthChecks),
      GlobalSnapshotEventsPublisherDaemon.make(queues.stateChannelOutput, queues.l1Output, services.gossip),
      services.consensus.daemon
    ).traverse(_.start).void

}
