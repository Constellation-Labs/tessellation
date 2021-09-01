package org.tesselation.modules

import cats.effect.kernel.Async
import cats.syntax.functor._

import org.tesselation.domain.cluster.{Cluster, ClusterStorage}
import org.tesselation.infrastructure.cluster.{Cluster, ClusterStorage}
import org.tesselation.infrastructure.healthcheck.{Services => HealthCheckServices}

object Services {

  def make[F[_]: Async](): F[Services[F]] =
    for {
      clusterStorage <- ClusterStorage.make[F]
      cluster = Cluster.make[F](clusterStorage)
      healthcheck = HealthCheckServices.make[F]
    } yield
      new Services[F](
        healthcheck = healthcheck,
        clusterStorage = clusterStorage,
        cluster = cluster
      ) {}
}

sealed abstract class Services[F[_]] private (
  val healthcheck: HealthCheckServices[F],
  val clusterStorage: ClusterStorage[F],
  val cluster: Cluster[F]
)
