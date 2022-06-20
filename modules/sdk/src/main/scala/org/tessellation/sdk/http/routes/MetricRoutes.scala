package org.tessellation.sdk.http.routes

import cats.effect.Async

import org.tessellation.sdk.infrastructure.metrics.Metrics

import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final case class MetricRoutes[F[_]: Async: Metrics]() extends Http4sDsl[F] {
  private[routes] val prefixPath = "/metrics"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root => Ok(Metrics[F].getAllAsText)
  }

  val routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )
}
