package org.tesselation.modules

import cats.effect.kernel.Async
import cats.syntax.functor._

import org.tesselation.config.types.AppConfig
import org.tesselation.domain.cluster.services.{Cluster, Session}
import org.tesselation.domain.healthcheck.HealthCheck
import org.tesselation.infrastructure.cluster.services.{Cluster, Session}
import org.tesselation.infrastructure.healthcheck.HealthCheck
import org.tesselation.schema.peer.PeerId

object Services {

  def make[F[_]: Async](
    cfg: AppConfig,
    nodeId: PeerId,
    storages: Storages[F]
  ): F[Services[F]] =
    for {
      _ <- Async[F].unit
      healthcheck = HealthCheck.make[F]
      session = Session.make[F](storages.session, storages.cluster)
      cluster = Cluster
        .make[F](cfg, nodeId, storages.session)
    } yield
      new Services[F](
        healthcheck = healthcheck,
        cluster = cluster,
        session = session
      ) {}
}

sealed abstract class Services[F[_]] private (
  val healthcheck: HealthCheck[F],
  val cluster: Cluster[F],
  val session: Session[F]
)
