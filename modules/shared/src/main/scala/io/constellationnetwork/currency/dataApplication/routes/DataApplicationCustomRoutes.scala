package io.constellationnetwork.currency.dataApplication.routes

import cats.Monad

import io.constellationnetwork.currency.dataApplication.ops.BaseDataApplicationSharedContextualOps
import io.constellationnetwork.currency.dataApplication.services.BaseDataApplicationService

import org.http4s.HttpRoutes
import org.http4s.server.Router

object DataApplicationCustomRoutes {
  def publicRoutes[F[_]: Monad, Context](
    maybeDataApplication: Option[BaseDataApplicationService[F] with BaseDataApplicationSharedContextualOps[F, Context]]
  )(implicit context: Context): HttpRoutes[F] =
    maybeDataApplication.map { da =>
      Router(da.routesPrefix.value -> da.routes)
    }.getOrElse(HttpRoutes.empty[F])
}
