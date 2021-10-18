package org.tesselation.http.routes

import cats.effect.Async

import org.tesselation.modules.Services

import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final case class MetricRoutes[F[_]: Async](
  services: Services[F]
) extends Http4sDsl[F] {
  private[routes] val prefixPath = "/metric"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root => Ok(services.metrics.getAll)
  }

  val routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )
}
