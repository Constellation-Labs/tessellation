package org.tessellation.modules

import cats.effect.kernel.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.domain.aci.StateChannelRunner
import org.tessellation.domain.healthcheck.HealthCheck
import org.tessellation.infrastructure.aci.StateChannelRunner
import org.tessellation.infrastructure.healthcheck.HealthCheck
import org.tessellation.infrastructure.metrics.Metrics
import org.tessellation.sdk.domain.cluster.services.{Cluster, Session}
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.modules.SdkServices

object Services {

  def make[F[_]: Async](
    sdkServices: SdkServices[F],
    queues: Queues[F]
  ): F[Services[F]] =
    for {
      metrics <- Metrics.make[F]
      healthcheck = HealthCheck.make[F]
      stateChannelRunner <- StateChannelRunner.make[F](queues.stateChannelOutput)
    } yield
      new Services[F](
        healthcheck = healthcheck,
        cluster = sdkServices.cluster,
        session = sdkServices.session,
        metrics = metrics,
        gossip = sdkServices.gossip,
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
