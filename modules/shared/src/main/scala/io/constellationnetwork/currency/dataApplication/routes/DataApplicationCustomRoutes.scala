package io.constellationnetwork.currency.dataApplication.routes

import cats.Monad
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.ops.BaseDataApplicationSharedContextualOps
import io.constellationnetwork.currency.dataApplication.services.BaseDataApplicationService

import org.http4s.HttpRoutes
import org.http4s.server.Router

object DataApplicationCustomRoutes {
  def publicRoutes[F[_]: Monad, Context](
    maybeDataApplication: Option[BaseDataApplicationService[F] with BaseDataApplicationSharedContextualOps[F, Context]]
  )(implicit context: Context): F[HttpRoutes[F]] =
    maybeDataApplication match {
      case Some(da) =>
        da.routesPrefix.map { routesPrefix =>
          Router(routesPrefix.value -> da.routes)
        }
      case None =>
        Monad[F].pure(HttpRoutes.empty[F])
    }
}
