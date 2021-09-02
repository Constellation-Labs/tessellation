package org.tesselation.modules

import cats.effect.kernel.Async
import cats.syntax.functor._

import org.tesselation.domain.cluster.Cluster
import org.tesselation.domain.healthcheck.HealthCheck
import org.tesselation.infrastructure.cluster.Cluster
import org.tesselation.infrastructure.healthcheck.HealthCheck

object Services {

  def make[F[_]: Async](storages: Storages[F]): F[Services[F]] =
    for {
      _ <- Async[F].unit
      cluster = Cluster.make[F](storages.cluster, storages.node)
      healthcheck = HealthCheck.make[F]
    } yield
      new Services[F](
        healthcheck = healthcheck,
        cluster = cluster
      ) {}
}

sealed abstract class Services[F[_]] private (
  val healthcheck: HealthCheck[F],
  val cluster: Cluster[F]
)
