package org.tessellation.node.shared.http.routes

import cats.effect.Async

import org.tessellation.node.shared.infrastructure.metrics.Metrics
import org.tessellation.routes.internal._

import eu.timepit.refined.auto._
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl

final case class MetricRoutes[F[_]: Async: Metrics]() extends Http4sDsl[F] with PublicRoutes[F] {
  protected[routes] val prefixPath: InternalUrlPrefix = "/metrics"

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root => Ok(Metrics[F].getAllAsText)
  }
}
